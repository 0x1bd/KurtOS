#define LIMINE_API_REVISION 3
#include "limine.h"
#include "kurtos.h"

extern void kotlin_main(void);
extern void kthread_init(void);
extern void (*__init_array_start[])(void);
extern void (*__init_array_end[])(void);
extern void __cpu_indicator_init(void);

extern uint8_t __eh_frame_start[];
extern void __register_frame_info(const void *begin, void *object);

extern uint8_t __tdata_start[];
extern uint8_t __tdata_end[];
extern uint8_t __tbss_end[];

kurtos_boot_info_t kurtos_boot_info;
uint64_t kurtos_lapic_base;
uint64_t kurtos_ticks;

__attribute__((used, section(".limine_requests")))
static volatile LIMINE_BASE_REVISION(2);

__attribute__((used, section(".limine_requests_start")))
static volatile LIMINE_REQUESTS_START_MARKER;

__attribute__((used, section(".limine_requests_end")))
static volatile LIMINE_REQUESTS_END_MARKER;

__attribute__((used, section(".limine_requests")))
static volatile struct limine_framebuffer_request framebuffer_request = {
    .id = LIMINE_FRAMEBUFFER_REQUEST, .revision = 0,
};

__attribute__((used, section(".limine_requests")))
static volatile struct limine_memmap_request memmap_request = {
    .id = LIMINE_MEMMAP_REQUEST, .revision = 0,
};

__attribute__((used, section(".limine_requests")))
static volatile struct limine_hhdm_request hhdm_request = {
    .id = LIMINE_HHDM_REQUEST, .revision = 0,
};

__attribute__((used, section(".limine_requests")))
static volatile struct limine_module_request module_request = {
    .id = LIMINE_MODULE_REQUEST, .revision = 0,
};

__attribute__((used, section(".limine_requests")))
static volatile struct limine_rsdp_request rsdp_request = {
    .id = LIMINE_RSDP_REQUEST, .revision = 0,
};

__attribute__((used, section(".limine_requests")))
static volatile struct limine_stack_size_request stack_size_request = {
    .id = LIMINE_STACK_SIZE_REQUEST, .revision = 0, .stack_size = 64 * 1024,
};

#define COM1 0x3F8

static inline void outb(uint16_t port, uint8_t value) {
    __asm__ volatile("outb %0, %1" : : "a"(value), "Nd"(port));
}

static inline uint8_t inb(uint16_t port) {
    uint8_t value;
    __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port));
    return value;
}

void debug_print(const char *msg) {
    while (*msg) {
        if (*msg == '\n') {
            for (int i = 0; i < 100000 && !(inb(COM1 + 5) & 0x20); i++) { }
            outb(COM1, '\r');
        }
        for (int i = 0; i < 100000 && !(inb(COM1 + 5) & 0x20); i++) { }
        outb(COM1, (uint8_t)*msg++);
    }
}

__attribute__((noreturn))
void hcf(void) {
    for (;;) __asm__ volatile("cli; hlt");
}

#define MAX_PAGE_POOL (64ULL * 1024 * 1024)
#define MIN_HEAP      (16ULL * 1024 * 1024)

#define ALIGN_UP(v, a)   (((v) + (a) - 1) & ~((a) - 1))
#define ALIGN_DOWN(v, a) ((v) & ~((a) - 1))

static void collect_memmap(void) {
    struct limine_memmap_response *res = memmap_request.response;
    if (res == 0) {
        debug_print("boot: no memory map\n");
        hcf();
    }

    uint64_t count = res->entry_count;
    if (count > KURTOS_MAX_MEMMAP) count = KURTOS_MAX_MEMMAP;
    kurtos_boot_info.memmap_count = count;

    uint64_t best_base = 0;
    uint64_t best_len = 0;

    for (uint64_t i = 0; i < res->entry_count; i++) {
        struct limine_memmap_entry *e = res->entries[i];

        if (i < count) {
            kurtos_boot_info.memmap[i].base = e->base;
            kurtos_boot_info.memmap[i].length = e->length;
            kurtos_boot_info.memmap[i].type = e->type;
        }

        if (e->type == LIMINE_MEMMAP_USABLE && e->length > best_len) {
            best_len = e->length;
            best_base = e->base;
        }
    }

    if (best_len < MIN_HEAP) {
        debug_print("boot: no usable memory region\n");
        hcf();
    }

    uint64_t hhdm = kurtos_boot_info.hhdm_offset;

    
    uint64_t heap_base = ALIGN_UP(best_base, 2ULL * 1024 * 1024);
    uint64_t region_end = best_base + best_len;
    if (heap_base >= region_end || region_end - heap_base < MIN_HEAP) {
        debug_print("boot: usable region too small once aligned\n");
        hcf();
    }

    uint64_t usable = region_end - heap_base;
    uint64_t pool = usable / 4;
    if (pool > MAX_PAGE_POOL) pool = MAX_PAGE_POOL;

    uint64_t heap_len = ALIGN_DOWN(usable - pool, 4096);

    kurtos_boot_info.heap_start = heap_base + hhdm;
    kurtos_boot_info.heap_end = heap_base + heap_len + hhdm;
    kurtos_boot_info.pages_start = heap_base + heap_len + hhdm;
    kurtos_boot_info.pages_end = ALIGN_DOWN(region_end, 4096) + hhdm;
}

static void collect_framebuffer(void) {
    struct limine_framebuffer_response *res = framebuffer_request.response;
    if (res == 0 || res->framebuffer_count == 0) return;

    struct limine_framebuffer *fb = res->framebuffers[0];
    if (fb->bpp != 32) return;

    kurtos_boot_info.fb_address = (uint64_t)fb->address;
    kurtos_boot_info.fb_width = fb->width;
    kurtos_boot_info.fb_height = fb->height;
    kurtos_boot_info.fb_pitch = fb->pitch;
    kurtos_boot_info.fb_bpp = fb->bpp;
    kurtos_boot_info.fb_red_shift = fb->red_mask_shift;
    kurtos_boot_info.fb_green_shift = fb->green_mask_shift;
    kurtos_boot_info.fb_blue_shift = fb->blue_mask_shift;
    kurtos_boot_info.fb_present = 1;
}

static void collect_module(void) {
    struct limine_module_response *res = module_request.response;
    if (res == 0 || res->module_count == 0) return;

    struct limine_file *file = res->modules[0];
    kurtos_boot_info.module_address = (uint64_t)file->address;
    kurtos_boot_info.module_size = file->size;
}

#define TLS_ARENA_SIZE 65536
static uint8_t tls_arena[TLS_ARENA_SIZE] __attribute__((aligned(64)));

uint64_t tls_main = 0;

extern int posix_memalign(void **memptr, size_t alignment, size_t size);
extern void free(void *p);

static void tls_fill(uint8_t *tp, size_t tls_size, size_t tdata_size) {
    for (size_t i = 0; i < tdata_size; i++) tp[i - tls_size] = __tdata_start[i];
    for (size_t i = tdata_size; i < tls_size; i++) tp[i - tls_size] = 0;
    *(uint64_t *)tp = (uint64_t)tp;
}

static void tls_init(void) {
    size_t tls_size = (size_t)(__tbss_end - __tdata_start);
    size_t tdata_size = (size_t)(__tdata_end - __tdata_start);

    if (tls_size + 64 > TLS_ARENA_SIZE) {
        debug_print("boot: tls arena too small\n");
        hcf();
    }

    uint8_t *tp = tls_arena + tls_size;
    tls_fill(tp, tls_size, tdata_size);
    tls_main = (uint64_t)tp;

    uint64_t base = (uint64_t)tp;
    __asm__ volatile("wrmsr" : : "c"(0xC0000100), "a"((uint32_t)base), "d"((uint32_t)(base >> 32)));
}

uint64_t tls_alloc(uint64_t *base_out) {
    size_t tls_size = (size_t)(__tbss_end - __tdata_start);
    size_t tdata_size = (size_t)(__tdata_end - __tdata_start);
    size_t bytes = tls_size + 64;

    void *raw = 0;
    if (posix_memalign(&raw, 64, bytes) != 0) return 0;
    uint8_t *block = (uint8_t *)raw;

    uint8_t *tp = block + tls_size;
    tls_fill(tp, tls_size, tdata_size);

    if (base_out) *base_out = (uint64_t)block;
    return (uint64_t)tp;
}

void tls_free(uint64_t base) {
    if (base) free((void *)base);
}

static void enable_sse(void) {
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

void kmain_entry(void) {
    enable_sse();

    debug_print("\n[kurtos] boot\n");

    if (!LIMINE_BASE_REVISION_SUPPORTED) {
        debug_print("boot: unsupported limine base revision\n");
        hcf();
    }

    if (hhdm_request.response == 0) {
        debug_print("boot: no hhdm\n");
        hcf();
    }
    kurtos_boot_info.hhdm_offset = hhdm_request.response->offset;

    if (rsdp_request.response != 0) {
        kurtos_boot_info.rsdp_address = rsdp_request.response->address;
    }

    collect_memmap();
    collect_framebuffer();
    collect_module();

    tls_init();
    heap_init(kurtos_boot_info.heap_start,
              kurtos_boot_info.heap_end - kurtos_boot_info.heap_start);
    kthread_init();

    __cpu_indicator_init();

    
    static char eh_object[64];
    __register_frame_info(__eh_frame_start, eh_object);

    for (void (**ctor)(void) = __init_array_start; ctor != __init_array_end; ctor++) {
        (*ctor)();
    }

    kotlin_main();

    debug_print("[kurtos] kotlin_main returned\n");
    hcf();
}
