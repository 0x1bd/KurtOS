#include <stddef.h>
#include <stdint.h>

extern void debug_print(const char *msg);
extern uint64_t kurtos_ticks;

void kthread_yield(void);
uint64_t kthread_self(void);
int kthread_create(uint64_t *out_id, void *(*start)(void *), void *arg);
int kthread_join(uint64_t id, void **retval);
int kthread_detach(uint64_t id);
void kthread_exit(void *retval);

#define EBUSY      16
#define EAGAIN     11
#define ETIMEDOUT  110

#define NS_PER_MS  1000000ULL
#define NS_PER_SEC 1000000000ULL

typedef struct {
    volatile uint64_t locked;
} kmutex_t;

typedef struct {
    volatile uint64_t generation;
} kcond_t;

static uint64_t now_nanos(void) {
    return kurtos_ticks * NS_PER_MS;
}

int sched_yield(void) {
    kthread_yield();
    return 0;
}

int pthread_mutex_init(void *m, const void *attr) {
    (void)attr;
    ((kmutex_t *)m)->locked = 0;
    return 0;
}

int pthread_mutex_destroy(void *m) {
    ((kmutex_t *)m)->locked = 0;
    return 0;
}

int pthread_mutex_lock(void *m) {
    kmutex_t *mutex = (kmutex_t *)m;
    while (mutex->locked) kthread_yield();
    mutex->locked = 1;
    return 0;
}

int pthread_mutex_trylock(void *m) {
    kmutex_t *mutex = (kmutex_t *)m;
    if (mutex->locked) return EBUSY;
    mutex->locked = 1;
    return 0;
}

int pthread_mutex_unlock(void *m) {
    ((kmutex_t *)m)->locked = 0;
    return 0;
}

int pthread_cond_init(void *c, const void *attr) {
    (void)attr;
    ((kcond_t *)c)->generation = 0;
    return 0;
}

int pthread_cond_destroy(void *c) {
    (void)c;
    return 0;
}

int pthread_cond_signal(void *c) {
    ((kcond_t *)c)->generation++;
    return 0;
}

int pthread_cond_broadcast(void *c) {
    ((kcond_t *)c)->generation++;
    return 0;
}

int pthread_cond_wait(void *c, void *m) {
    kcond_t *cond = (kcond_t *)c;
    uint64_t seen = cond->generation;

    pthread_mutex_unlock(m);
    while (cond->generation == seen) kthread_yield();
    pthread_mutex_lock(m);

    return 0;
}

int pthread_cond_timedwait(void *c, void *m, const void *abstime) {
    kcond_t *cond = (kcond_t *)c;
    const int64_t *ts = (const int64_t *)abstime;

    uint64_t deadline = (uint64_t)ts[0] * NS_PER_SEC + (uint64_t)ts[1];
    uint64_t seen = cond->generation;
    int timed_out = 0;

    pthread_mutex_unlock(m);
    while (cond->generation == seen) {
        if (now_nanos() >= deadline) {
            timed_out = 1;
            break;
        }
        kthread_yield();
    }
    pthread_mutex_lock(m);

    return timed_out ? ETIMEDOUT : 0;
}

unsigned long pthread_self(void) {
    return (unsigned long)kthread_self();
}

int pthread_create(void *tid, const void *attr, void *(*start)(void *), void *arg) {
    (void)attr;

    uint64_t id = 0;
    if (kthread_create(&id, start, arg) != 0) return EAGAIN;

    if (tid) *(uint64_t *)tid = id;
    return 0;
}

int pthread_join(unsigned long thread, void **retval) {
    return kthread_join((uint64_t)thread, retval) == 0 ? 0 : EAGAIN;
}

int pthread_detach(unsigned long thread) {
    return kthread_detach((uint64_t)thread) == 0 ? 0 : EAGAIN;
}

void pthread_exit(void *retval) {
    kthread_exit(retval);
    for (;;) __asm__ volatile("cli; hlt");
}

int clock_gettime(int clk_id, void *tp) {
    (void)clk_id;
    if (!tp) return -1;

    uint64_t ns = now_nanos();
    int64_t *ts = (int64_t *)tp;
    ts[0] = (int64_t)(ns / NS_PER_SEC);
    ts[1] = (int64_t)(ns % NS_PER_SEC);
    return 0;
}

long long _ZNSt6chrono3_V212steady_clock3nowEv(void) {
    return (long long)now_nanos();
}

long long _ZNSt6chrono3_V212system_clock3nowEv(void) {
    return (long long)now_nanos();
}

static void *thread_state_entry(void *state) {
    void **vtable = *(void ***)state;

    void (*run)(void *) = (void (*)(void *))vtable[2];
    void (*dispose)(void *) = (void (*)(void *))vtable[1];

    run(state);
    dispose(state);
    return 0;
}

void _ZNSt6thread15_M_start_threadESt10unique_ptrINS_6_StateESt14default_deleteIS1_EEPFvvE(
    void *self, void *state_ref, void *depend) {
    (void)depend;

    void **holder = (void **)state_ref;
    void *state = *holder;

    uint64_t id = 0;
    if (kthread_create(&id, thread_state_entry, state) != 0) {
        debug_print("std::thread: cannot start thread\n");
        for (;;) __asm__ volatile("cli; hlt");
    }

    *holder = 0;
    *(uint64_t *)self = id;
}

void _ZNSt6thread4joinEv(void *self) {
    uint64_t id = *(uint64_t *)self;
    if (id) kthread_join(id, 0);
    *(uint64_t *)self = 0;
}

void _ZNSt6thread6detachEv(void *self) {
    uint64_t id = *(uint64_t *)self;
    if (id) kthread_detach(id);
    *(uint64_t *)self = 0;
}

void _ZNSt18condition_variableC1Ev(void *self) {
    pthread_cond_init(self, 0);
}

void _ZNSt18condition_variableD1Ev(void *self) {
    pthread_cond_destroy(self);
}

void _ZNSt18condition_variable10notify_oneEv(void *self) {
    pthread_cond_signal(self);
}

void _ZNSt18condition_variable10notify_allEv(void *self) {
    pthread_cond_broadcast(self);
}

void _ZNSt18condition_variable4waitERSt11unique_lockISt5mutexE(void *self, void *lock) {
    void *mutex = *(void **)lock;
    pthread_cond_wait(self, mutex);
}
