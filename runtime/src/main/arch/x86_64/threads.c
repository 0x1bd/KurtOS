#include <stddef.h>
#include <stdint.h>

#include "threads.h"

extern void debug_print(const char *msg);
extern void *malloc(size_t n);
extern void free(void *p);
extern uint64_t tls_alloc(uint64_t *base_out);
extern void tls_free(uint64_t base);
extern uint64_t tls_main;

extern int kurtos_smp_available(void);
extern int kurtos_smp_run(void *(*fn)(void *), void *arg, uint64_t tls_tp, uint64_t thread_id,
                          void *volatile *retval_slot, volatile int *state_slot, int done_state);
extern void kurtos_smp_thread_exit(void *ret);

void kthread_switch(uint64_t *save_sp, uint64_t new_sp, uint64_t new_tp);

#define MAX_THREADS 32
#define STACK_SIZE  (128 * 1024)

enum {
    T_FREE = 0,
    T_READY,
    T_RUNNING,
    T_DONE,
    T_AP,
};

typedef struct {
    uint64_t sp;
    uint64_t tp;
    uint64_t tls_base;
    uint8_t *stack;
    void *(*start)(void *);
    void *arg;
    void *volatile retval;
    volatile int state;
    int detached;
} kthread_t;

static kthread_t threads[MAX_THREADS];
static int current_index;
static int scheduler_ready;

static __thread uint64_t tls_thread_id;
static __thread int tls_on_ap;

void kthread_init(void) {
    for (int i = 0; i < MAX_THREADS; i++) threads[i].state = T_FREE;

    threads[0].state = T_RUNNING;
    threads[0].tp = tls_main;
    threads[0].stack = 0;
    threads[0].tls_base = 0;

    current_index = 0;
    scheduler_ready = 1;
    tls_thread_id = 1;
}

void kthread_mark_ap(uint64_t id) {
    tls_thread_id = id;
    tls_on_ap = 1;
}

int kthread_on_ap(void) {
    return tls_on_ap;
}

uint64_t kthread_self(void) {
    if (tls_thread_id != 0) return tls_thread_id;
    return (uint64_t)(current_index + 1);
}

static void kthread_reap(kthread_t *t) {
    if (t->stack) free(t->stack);
    tls_free(t->tls_base);
    t->stack = 0;
    t->tls_base = 0;
    t->state = T_FREE;
}

void kthread_yield(void) {
    if (tls_on_ap) {
        __asm__ volatile("pause");
        return;
    }
    if (!scheduler_ready) return;

    int from = current_index;
    int next = from;

    for (int i = 1; i <= MAX_THREADS; i++) {
        int c = (from + i) % MAX_THREADS;
        if (threads[c].state == T_READY) {
            next = c;
            break;
        }
    }

    if (next == from) {
        __asm__ volatile("sti; hlt");
        return;
    }

    kthread_t *prev = &threads[from];
    kthread_t *to = &threads[next];

    if (prev->state == T_RUNNING) prev->state = T_READY;
    to->state = T_RUNNING;
    current_index = next;

    kthread_switch(&prev->sp, to->sp, to->tp);
}

static void kthread_trampoline(void) {
    kthread_t *self = &threads[current_index];
    tls_thread_id = (uint64_t)(current_index + 1);

    self->retval = self->start(self->arg);
    self->state = T_DONE;

    if (self->detached) kthread_reap(self);

    for (;;) kthread_yield();
}

static void reap_finished_detached(void) {
    for (int i = 1; i < MAX_THREADS; i++) {
        if (threads[i].state == T_DONE && threads[i].detached) kthread_reap(&threads[i]);
    }
}

int kthread_create(uint64_t *out_id, void *(*start)(void *), void *arg) {
    if (!scheduler_ready) return -1;

    reap_finished_detached();

    int idx = -1;
    for (int i = 1; i < MAX_THREADS; i++) {
        if (threads[i].state == T_FREE) {
            idx = i;
            break;
        }
    }
    if (idx < 0) {
        debug_print("kthread_create: no free slot\n");
        return -1;
    }

    kthread_t *t = &threads[idx];

    if (kurtos_smp_available()) {
        uint64_t base = 0;
        uint64_t tp = tls_alloc(&base);
        if (tp) {
            t->stack = 0;
            t->tp = tp;
            t->tls_base = base;
            t->start = start;
            t->arg = arg;
            t->retval = 0;
            t->detached = 0;
            t->state = T_AP;

            if (kurtos_smp_run(start, arg, tp, (uint64_t)(idx + 1),
                               &t->retval, &t->state, T_DONE) == 0) {
                if (out_id) *out_id = (uint64_t)(idx + 1);
                return 0;
            }

            t->state = T_FREE;
            tls_free(base);
        }
    }

    t->stack = (uint8_t *)malloc(STACK_SIZE);
    if (!t->stack) {
        debug_print("kthread_create: no stack\n");
        return -1;
    }

    t->tp = tls_alloc(&t->tls_base);
    if (!t->tp) {
        free(t->stack);
        t->stack = 0;
        debug_print("kthread_create: no tls\n");
        return -1;
    }

    t->start = start;
    t->arg = arg;
    t->retval = 0;
    t->detached = 0;

    uint8_t *top = t->stack + STACK_SIZE;
    top = (uint8_t *)((uintptr_t)top & ~(uintptr_t)15);

    uint64_t *sp = (uint64_t *)top;
    *(--sp) = 0;
    *(--sp) = (uint64_t)(uintptr_t)kthread_trampoline;
    for (int i = 0; i < 6; i++) *(--sp) = 0;

    t->sp = (uint64_t)(uintptr_t)sp;
    t->state = T_READY;

    if (out_id) *out_id = (uint64_t)(idx + 1);
    return 0;
}

int kthread_join(uint64_t id, void **retval) {
    int idx = (int)id - 1;
    if (idx <= 0 || idx >= MAX_THREADS) return -1;

    kthread_t *t = &threads[idx];
    while (t->state != T_DONE && t->state != T_FREE) kthread_yield();

    if (retval) *retval = t->retval;
    if (t->state == T_DONE) kthread_reap(t);
    return 0;
}

int kthread_detach(uint64_t id) {
    int idx = (int)id - 1;
    if (idx <= 0 || idx >= MAX_THREADS) return -1;

    kthread_t *t = &threads[idx];
    if (t->state == T_DONE) {
        kthread_reap(t);
    } else {
        t->detached = 1;
    }
    return 0;
}

void kthread_exit(void *retval) {
    if (tls_on_ap) kurtos_smp_thread_exit(retval);

    kthread_t *self = &threads[current_index];

    if (current_index == 0) {
        debug_print("kthread_exit: main thread exited\n");
        for (;;) __asm__ volatile("cli; hlt");
    }

    self->retval = retval;
    self->state = T_DONE;

    if (self->detached) kthread_reap(self);

    for (;;) kthread_yield();
}
