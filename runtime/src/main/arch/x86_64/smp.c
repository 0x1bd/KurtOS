#define LIMINE_API_REVISION 3
#include "limine.h"
#include "kurtos.h"

extern void *malloc(size_t n);
extern uint64_t tls_alloc(uint64_t *base_out);
extern void kthread_mark_ap(uint64_t id);

__attribute__((used, section(".limine_requests")))
static volatile struct limine_mp_request mp_request = {
    .id = LIMINE_MP_REQUEST, .revision = 0,
};

#define MAX_APS       15
#define IST_SIZE      16384
#define GDT_ENTRIES   7
#define TSS_SIZE      104

enum {
    AP_OFFLINE = 0,
    AP_IDLE = 1,
    AP_CLAIMED = 2,
    AP_BUSY = 3,
};

typedef struct ap {
    struct limine_mp_info *info;
    uint8_t *gdt;
    uint8_t *tss;
    uint8_t *gdt_descriptor;
    uint64_t idle_tls;
    uint64_t stack_top;

    volatile uint64_t job_seq;
    uint64_t done_seq;
    void *(*fn)(void *);
    void *arg;
    uint64_t thread_tls;
    uint64_t thread_id;
    void *volatile *retval_slot;
    volatile int *state_slot;
    int done_state;

    volatile int state;
} ap_t;

static ap_t aps[MAX_APS];
static int ap_total;
static volatile int ap_online;
static volatile int dispatch_enabled;
static uint8_t idt_descriptor[10];

static __thread ap_t *tls_ap;

static inline void wrmsr_fs(uint64_t base) {
    __asm__ volatile("wrmsr" : : "c"(0xC0000100), "a"((uint32_t)base), "d"((uint32_t)(base >> 32)));
}

static inline void cpu_pause(void) {
    __asm__ volatile("pause");
}

static void ap_enable_sse(void) {
    uint64_t cr0, cr4;

    __asm__ volatile("mov %%cr0, %0" : "=r"(cr0));
    cr0 &= ~(1ULL << 2);
    cr0 |= (1ULL << 1);
    __asm__ volatile("mov %0, %%cr0" : : "r"(cr0));

    __asm__ volatile("mov %%cr4, %0" : "=r"(cr4));
    cr4 |= (1ULL << 9);
    cr4 |= (1ULL << 10);
    __asm__ volatile("mov %0, %%cr4" : : "r"(cr4));
}

static uint64_t gdt_entry(uint32_t access, uint32_t flags) {
    uint64_t d = 0xFFFFULL;
    d |= (uint64_t)access << 40;
    d |= 0xFULL << 48;
    d |= ((uint64_t)flags & 0xF) << 52;
    return d;
}

static int build_ap_tables(ap_t *ap) {
    ap->gdt = (uint8_t *)malloc(GDT_ENTRIES * 8 + TSS_SIZE + 16);
    if (!ap->gdt) return -1;

    ap->tss = ap->gdt + GDT_ENTRIES * 8;
    ap->gdt_descriptor = ap->tss + TSS_SIZE;

    for (int i = 0; i < TSS_SIZE; i++) ap->tss[i] = 0;

    for (int ist = 0; ist < 3; ist++) {
        uint8_t *stack = (uint8_t *)malloc(IST_SIZE);
        if (!stack) return -1;
        uint64_t end = (uint64_t)(uintptr_t)(stack + IST_SIZE) & ~0xFULL;
        *(uint64_t *)(ap->tss + 36 + ist * 8) = end;
    }
    *(uint16_t *)(ap->tss + 102) = TSS_SIZE;

    uint64_t *gdt = (uint64_t *)ap->gdt;
    gdt[0] = 0;
    gdt[1] = gdt_entry(0x9A, 0xA);
    gdt[2] = gdt_entry(0x92, 0xC);
    gdt[3] = gdt_entry(0xF2, 0xC);
    gdt[4] = gdt_entry(0xFA, 0xA);

    uint64_t base = (uint64_t)(uintptr_t)ap->tss;
    uint64_t limit = TSS_SIZE - 1;
    uint64_t low = 0;
    low |= limit & 0xFFFF;
    low |= (base & 0xFFFFFF) << 16;
    low |= 0x89ULL << 40;
    low |= ((limit >> 16) & 0xF) << 48;
    low |= ((base >> 24) & 0xFF) << 56;
    gdt[5] = low;
    gdt[6] = base >> 32;

    *(uint16_t *)ap->gdt_descriptor = GDT_ENTRIES * 8 - 1;
    *(uint64_t *)(ap->gdt_descriptor + 2) = (uint64_t)(uintptr_t)ap->gdt;

    ap->idle_tls = tls_alloc(0);
    if (!ap->idle_tls) return -1;

    return 0;
}

static void smp_finish(ap_t *ap, void *ret) {
    wrmsr_fs(ap->idle_tls);
    *ap->retval_slot = ret;
    __atomic_store_n(ap->state_slot, ap->done_state, __ATOMIC_RELEASE);
    __atomic_store_n(&ap->state, AP_IDLE, __ATOMIC_RELEASE);
}

static void ap_loop(ap_t *ap) {
    for (;;) {
        uint64_t seq = __atomic_load_n(&ap->job_seq, __ATOMIC_ACQUIRE);
        if (seq == ap->done_seq) {
            cpu_pause();
            continue;
        }
        ap->done_seq = seq;

        wrmsr_fs(ap->thread_tls);
        tls_ap = ap;
        kthread_mark_ap(ap->thread_id);

        void *ret = ap->fn(ap->arg);
        smp_finish(ap, ret);
    }
}

__attribute__((noreturn))
static void ap_enter_loop(ap_t *ap) {
    __asm__ volatile(
        "mov %0, %%rsp\n\t"
        "jmp *%1"
        : : "r"(ap->stack_top), "r"(ap_loop), "D"(ap) : "memory");
    __builtin_unreachable();
}

static void ap_entry(struct limine_mp_info *info) {
    __asm__ volatile("cli");

    ap_t *ap = (ap_t *)(uintptr_t)info->extra_argument;

    ap_enable_sse();
    lgdt_load(ap->gdt_descriptor, 0x08, 0x10);
    ltr_load(0x28);
    lidt_load(idt_descriptor);
    wrmsr_fs(ap->idle_tls);

    uint64_t rsp;
    __asm__ volatile("mov %%rsp, %0" : "=r"(rsp));
    ap->stack_top = (rsp & ~0xFULL) - 8;

    __atomic_store_n(&ap->state, AP_IDLE, __ATOMIC_RELEASE);
    __atomic_add_fetch(&ap_online, 1, __ATOMIC_ACQ_REL);

    ap_enter_loop(ap);
}

int kurtos_smp_start(void) {
    struct limine_mp_response *res = mp_request.response;
    if (res == 0) {
        debug_print("[smp] no mp response\n");
        return 0;
    }

    __asm__ volatile("sidt %0" : "=m"(*(struct { uint16_t l; uint64_t b; } __attribute__((packed)) *)idt_descriptor));

    for (uint64_t i = 0; i < res->cpu_count && ap_total < MAX_APS; i++) {
        struct limine_mp_info *cpu = res->cpus[i];
        if (cpu->lapic_id == res->bsp_lapic_id) continue;

        ap_t *ap = &aps[ap_total];
        ap->info = cpu;
        if (build_ap_tables(ap) != 0) {
            debug_print("[smp] ap table allocation failed\n");
            break;
        }

        cpu->extra_argument = (uint64_t)(uintptr_t)ap;
        ap_total++;
        __atomic_store_n(&cpu->goto_address, ap_entry, __ATOMIC_RELEASE);
    }

    for (uint64_t spin = 0; spin < 4000000000ULL; spin++) {
        if (__atomic_load_n(&ap_online, __ATOMIC_ACQUIRE) >= ap_total) break;
        cpu_pause();
    }

    __atomic_store_n(&dispatch_enabled, 1, __ATOMIC_RELEASE);

    debug_print("[smp] application processors online\n");
    return __atomic_load_n(&ap_online, __ATOMIC_ACQUIRE);
}

int kurtos_smp_cpus(void) {
    return 1 + __atomic_load_n(&ap_online, __ATOMIC_ACQUIRE);
}

int kurtos_smp_available(void) {
    if (!__atomic_load_n(&dispatch_enabled, __ATOMIC_ACQUIRE)) return 0;
    for (int i = 0; i < ap_total; i++) {
        if (__atomic_load_n(&aps[i].state, __ATOMIC_ACQUIRE) == AP_IDLE) return 1;
    }
    return 0;
}

int kurtos_smp_run(void *(*fn)(void *), void *arg, uint64_t tls_tp, uint64_t thread_id,
                   void *volatile *retval_slot, volatile int *state_slot, int done_state) {
    if (!__atomic_load_n(&dispatch_enabled, __ATOMIC_ACQUIRE)) return -1;

    for (int i = 0; i < ap_total; i++) {
        ap_t *ap = &aps[i];
        int idle = AP_IDLE;
        if (!__atomic_compare_exchange_n(&ap->state, &idle, AP_CLAIMED, 0,
                                         __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
            continue;
        }

        ap->fn = fn;
        ap->arg = arg;
        ap->thread_tls = tls_tp;
        ap->thread_id = thread_id;
        ap->retval_slot = retval_slot;
        ap->state_slot = state_slot;
        ap->done_state = done_state;

        __atomic_store_n(&ap->state, AP_BUSY, __ATOMIC_RELEASE);
        __atomic_add_fetch(&ap->job_seq, 1, __ATOMIC_ACQ_REL);
        return 0;
    }
    return -1;
}

__attribute__((noreturn))
void kurtos_smp_thread_exit(void *ret) {
    ap_t *ap = tls_ap;
    smp_finish(ap, ret);
    ap_enter_loop(ap);
}
