#include <stddef.h>
#include <stdint.h>

#include "../arch/x86_64/kurtos.h"

extern void *memcpy(void *dst, const void *src, size_t n);
extern void *memset(void *s, int c, size_t n);
extern void debug_print(const char *msg);

#define PROT_EXEC_BIT 0x4

#define PTE_PRESENT   0x1ULL
#define PTE_PS        0x80ULL
#define PTE_ADDR_MASK 0x000FFFFFFFFFF000ULL
#define PTE_NX        (1ULL << 63)

static inline uint64_t read_cr3(void) {
    uint64_t v;
    __asm__ volatile("mov %%cr3, %0" : "=r"(v));
    return v;
}

static inline void invlpg(uint64_t va) {
    __asm__ volatile("invlpg (%0)" : : "r"(va) : "memory");
}

static void make_executable(uint64_t addr, uint64_t len) {
    uint64_t hhdm = kurtos_boot_info.hhdm_offset;
    if (hhdm == 0 || len == 0) return;

    uint64_t cr3 = read_cr3() & PTE_ADDR_MASK;
    uint64_t va = addr & ~0xFFFULL;
    uint64_t end = (addr + len + 0xFFFULL) & ~0xFFFULL;

    while (va < end) {
        uint64_t *pml4 = (uint64_t *)(cr3 + hhdm);
        uint64_t e4 = pml4[(va >> 39) & 0x1FF];
        if (!(e4 & PTE_PRESENT)) { va += 0x1000ULL; continue; }

        uint64_t *pdpt = (uint64_t *)((e4 & PTE_ADDR_MASK) + hhdm);
        uint64_t i3 = (va >> 30) & 0x1FF;
        uint64_t e3 = pdpt[i3];
        if (!(e3 & PTE_PRESENT)) { va += 0x1000ULL; continue; }
        if (e3 & PTE_PS) {
            if (e3 & PTE_NX) { pdpt[i3] = e3 & ~PTE_NX; invlpg(va); }
            va = (va & ~0x3FFFFFFFULL) + 0x40000000ULL;
            continue;
        }

        uint64_t *pd = (uint64_t *)((e3 & PTE_ADDR_MASK) + hhdm);
        uint64_t i2 = (va >> 21) & 0x1FF;
        uint64_t e2 = pd[i2];
        if (!(e2 & PTE_PRESENT)) { va += 0x1000ULL; continue; }
        if (e2 & PTE_PS) {
            if (e2 & PTE_NX) { pd[i2] = e2 & ~PTE_NX; invlpg(va); }
            va = (va & ~0x1FFFFFULL) + 0x200000ULL;
            continue;
        }

        uint64_t *pt = (uint64_t *)((e2 & PTE_ADDR_MASK) + hhdm);
        uint64_t i1 = (va >> 12) & 0x1FF;
        uint64_t e1 = pt[i1];
        if ((e1 & PTE_PRESENT) && (e1 & PTE_NX)) { pt[i1] = e1 & ~PTE_NX; invlpg(va); }
        va += 0x1000ULL;
    }
}

#define ALIGN_LOG2      4
#define ALIGN_SIZE      ((uint64_t)1 << ALIGN_LOG2)
#define SL_LOG2         4
#define SL_COUNT        (1 << SL_LOG2)
#define FL_SHIFT        (SL_LOG2 + ALIGN_LOG2)
#define FL_MAX          36
#define FL_COUNT        (FL_MAX - (FL_SHIFT - 1) + 1)
#define SMALL_BLOCK     ((uint64_t)1 << FL_SHIFT)

#define BLOCK_FREE      ((uint64_t)1)
#define BLOCK_PREV_FREE ((uint64_t)2)
#define BLOCK_FLAGS     (BLOCK_FREE | BLOCK_PREV_FREE)

typedef struct block {
    uint64_t prev_size;
    uint64_t size;
    struct block *next_free;
    struct block *prev_free;
} block_t;

#define HDR_SIZE    ((uint64_t)16)
#define MIN_PAYLOAD ((uint64_t)16)

static uint32_t fl_bitmap;
static uint32_t sl_bitmap[FL_COUNT];
static block_t *free_lists[FL_COUNT][SL_COUNT];

static uint8_t *heap_base;
static uint8_t *heap_limit;
static block_t *heap_sentinel;
static uint64_t live_bytes;

int heap_ready = 0;

static inline int fls64(uint64_t x) {
    return 63 - __builtin_clzll(x);
}

static inline int ffs32(uint32_t x) {
    return __builtin_ctz(x);
}

static inline uint64_t payload_of(const block_t *b) {
    return b->size & ~BLOCK_FLAGS;
}

static inline int is_free(const block_t *b) {
    return (b->size & BLOCK_FREE) != 0;
}

static inline int prev_is_free(const block_t *b) {
    return (b->size & BLOCK_PREV_FREE) != 0;
}

static inline block_t *next_block(block_t *b) {
    return (block_t *)((uint8_t *)b + HDR_SIZE + payload_of(b));
}

static inline block_t *prev_block(block_t *b) {
    return (block_t *)((uint8_t *)b - b->prev_size);
}

static inline void *payload_ptr(block_t *b) {
    return (void *)((uint8_t *)b + HDR_SIZE);
}

static inline block_t *block_of(void *p) {
    return (block_t *)((uint8_t *)p - HDR_SIZE);
}

static inline uint64_t adjust_size(uint64_t n) {
    uint64_t s = (n + ALIGN_SIZE - 1) & ~(ALIGN_SIZE - 1);
    return s < MIN_PAYLOAD ? MIN_PAYLOAD : s;
}

static void mapping_insert(uint64_t size, int *fl, int *sl) {
    if (size < SMALL_BLOCK) {
        *fl = 0;
        *sl = (int)(size / (SMALL_BLOCK / SL_COUNT));
    } else {
        int f = fls64(size);
        *sl = (int)((size >> (f - SL_LOG2)) - SL_COUNT);
        *fl = f - (FL_SHIFT - 1);
    }
}

static void mapping_search(uint64_t size, int *fl, int *sl) {
    if (size >= SMALL_BLOCK) {
        uint64_t round = ((uint64_t)1 << (fls64(size) - SL_LOG2)) - 1;
        size += round;
    }
    mapping_insert(size, fl, sl);
}

static void insert_free(block_t *b) {
    int fl, sl;
    mapping_insert(payload_of(b), &fl, &sl);

    block_t *head = free_lists[fl][sl];
    b->next_free = head;
    b->prev_free = (block_t *)0;
    if (head) head->prev_free = b;
    free_lists[fl][sl] = b;

    fl_bitmap |= (uint32_t)1 << fl;
    sl_bitmap[fl] |= (uint32_t)1 << sl;
}

static void remove_free(block_t *b) {
    int fl, sl;
    mapping_insert(payload_of(b), &fl, &sl);

    if (b->prev_free) b->prev_free->next_free = b->next_free;
    if (b->next_free) b->next_free->prev_free = b->prev_free;

    if (free_lists[fl][sl] == b) {
        free_lists[fl][sl] = b->next_free;
        if (!b->next_free) {
            sl_bitmap[fl] &= ~((uint32_t)1 << sl);
            if (!sl_bitmap[fl]) fl_bitmap &= ~((uint32_t)1 << fl);
        }
    }
}

static block_t *find_suitable(uint64_t size) {
    int fl, sl;
    mapping_search(size, &fl, &sl);
    if (fl >= FL_COUNT) return (block_t *)0;

    uint32_t map = sl_bitmap[fl] & (~(uint32_t)0 << sl);
    if (!map) {
        uint32_t fmap = fl_bitmap & (~(uint32_t)0 << (fl + 1));
        if (!fmap) return (block_t *)0;
        fl = ffs32(fmap);
        map = sl_bitmap[fl];
        if (!map) return (block_t *)0;
    }
    sl = ffs32(map);
    return free_lists[fl][sl];
}

static void mark_used(block_t *b) {
    b->size &= ~BLOCK_FREE;
    block_t *n = next_block(b);
    n->size &= ~BLOCK_PREV_FREE;
}

static void mark_free(block_t *b) {
    b->size |= BLOCK_FREE;
    block_t *n = next_block(b);
    n->size |= BLOCK_PREV_FREE;
    n->prev_size = HDR_SIZE + payload_of(b);
}

static block_t *merge_next(block_t *b) {
    block_t *n = next_block(b);
    if (n == heap_sentinel || !is_free(n)) return b;
    remove_free(n);
    b->size = (payload_of(b) + HDR_SIZE + payload_of(n)) | (b->size & BLOCK_FLAGS);
    return b;
}

static block_t *merge_prev(block_t *b) {
    if (!prev_is_free(b)) return b;
    block_t *p = prev_block(b);
    remove_free(p);
    p->size = (payload_of(p) + HDR_SIZE + payload_of(b)) | (p->size & BLOCK_FLAGS);
    return p;
}

static void release(block_t *b) {
    mark_free(b);
    b = merge_prev(b);
    b = merge_next(b);
    mark_free(b);
    insert_free(b);
}

static void split_block(block_t *b, uint64_t size) {
    uint64_t total = payload_of(b);
    if (total < size + HDR_SIZE + MIN_PAYLOAD) return;

    uint64_t rest = total - size - HDR_SIZE;
    b->size = size | (b->size & BLOCK_FLAGS);

    block_t *r = (block_t *)((uint8_t *)b + HDR_SIZE + size);
    r->prev_size = HDR_SIZE + size;
    r->size = rest;

    mark_free(r);
    r = merge_next(r);
    mark_free(r);
    insert_free(r);
}

void heap_init(uint64_t base, uint64_t size) {
    heap_ready = 0;
    fl_bitmap = 0;
    live_bytes = 0;

    for (int i = 0; i < FL_COUNT; i++) {
        sl_bitmap[i] = 0;
        for (int j = 0; j < SL_COUNT; j++) free_lists[i][j] = (block_t *)0;
    }

    uint8_t *start = (uint8_t *)((base + ALIGN_SIZE - 1) & ~(ALIGN_SIZE - 1));
    uint8_t *end = (uint8_t *)((base + size) & ~(ALIGN_SIZE - 1));

    if (end < start || (uint64_t)(end - start) < 4 * HDR_SIZE + MIN_PAYLOAD) {
        debug_print("heap_init: region too small\n");
        return;
    }

    heap_base = start;
    heap_limit = end;
    heap_sentinel = (block_t *)(end - HDR_SIZE);

    block_t *b = (block_t *)start;
    uint64_t payload = (uint64_t)((uint8_t *)heap_sentinel - start) - HDR_SIZE;

    b->prev_size = 0;
    b->size = payload;

    heap_sentinel->prev_size = HDR_SIZE + payload;
    heap_sentinel->size = 0;

    mark_free(b);
    insert_free(b);

    heap_ready = 1;
}

uint64_t heap_used(void) {
    return live_bytes;
}

uint64_t heap_total(void) {
    return heap_ready ? (uint64_t)(heap_limit - heap_base) : 0;
}

void *malloc(size_t n) {
    if (!heap_ready || n == 0) return (void *)0;

    uint64_t size = adjust_size((uint64_t)n);
    block_t *b = find_suitable(size);
    if (!b) {
        debug_print("malloc: out of memory\n");
        return (void *)0;
    }

    remove_free(b);
    mark_used(b);
    split_block(b, size);
    live_bytes += payload_of(b);

    return payload_ptr(b);
}

void free(void *ptr) {
    if (!ptr || !heap_ready) return;

    block_t *b = block_of(ptr);
    if (is_free(b)) return;

    live_bytes -= payload_of(b);
    release(b);
}

void *calloc(size_t nmemb, size_t size) {
    if (nmemb != 0 && size > (size_t)-1 / nmemb) return (void *)0;

    size_t total = nmemb * size;
    void *p = malloc(total);
    if (p) memset(p, 0, total);
    return p;
}

void *realloc(void *ptr, size_t size) {
    if (!ptr) return malloc(size);
    if (size == 0) {
        free(ptr);
        return (void *)0;
    }

    block_t *b = block_of(ptr);
    uint64_t need = adjust_size((uint64_t)size);
    uint64_t cur = payload_of(b);

    if (need <= cur) {
        split_block(b, need);
        live_bytes -= cur - payload_of(b);
        return ptr;
    }

    block_t *n = next_block(b);
    if (n != heap_sentinel && is_free(n) && cur + HDR_SIZE + payload_of(n) >= need) {
        remove_free(n);
        b->size = (cur + HDR_SIZE + payload_of(n)) | (b->size & BLOCK_FLAGS);
        mark_used(b);
        split_block(b, need);
        live_bytes += payload_of(b) - cur;
        return ptr;
    }

    void *np = malloc(size);
    if (!np) return (void *)0;

    memcpy(np, ptr, cur);
    free(ptr);
    return np;
}

int posix_memalign(void **memptr, size_t alignment, size_t size) {
    if (!memptr) return 22;
    if (alignment < sizeof(void *) || (alignment & (alignment - 1)) != 0) return 22;
    if (!heap_ready) return 12;

    if (size == 0) {
        *memptr = (void *)0;
        return 0;
    }

    if (alignment <= ALIGN_SIZE) {
        void *p = malloc(size);
        if (!p) return 12;
        *memptr = p;
        return 0;
    }

    uint64_t need = adjust_size((uint64_t)size);
    uint64_t slack = (uint64_t)alignment + HDR_SIZE + MIN_PAYLOAD + ALIGN_SIZE;

    block_t *b = find_suitable(need + slack);
    if (!b) {
        debug_print("posix_memalign: out of memory\n");
        return 12;
    }

    remove_free(b);
    mark_used(b);

    uint64_t start = (uint64_t)(uintptr_t)payload_ptr(b);
    uint64_t aligned = (start + alignment - 1) & ~((uint64_t)alignment - 1);

    if (aligned != start && aligned - start < HDR_SIZE + MIN_PAYLOAD) {
        aligned = (start + HDR_SIZE + MIN_PAYLOAD + alignment - 1) & ~((uint64_t)alignment - 1);
    }

    if (aligned != start) {
        uint64_t total = payload_of(b);
        uint64_t lead = aligned - start - HDR_SIZE;
        uint64_t rest = total - lead - HDR_SIZE;

        block_t *r = (block_t *)(uintptr_t)(aligned - HDR_SIZE);
        b->size = lead | (b->size & BLOCK_FLAGS);
        r->prev_size = HDR_SIZE + lead;
        r->size = rest;

        mark_used(r);
        release(b);
        b = r;
    }

    split_block(b, need);
    live_bytes += payload_of(b);

    *memptr = payload_ptr(b);
    return 0;
}

void *mmap(void *addr, size_t length, int prot, int flags, int fd, long offset) {
    (void)addr; (void)flags; (void)fd; (void)offset;

    void *p = (void *)0;
    if (posix_memalign(&p, 4096, length) != 0) return (void *)-1;

    memset(p, 0, length);
    if (prot & PROT_EXEC_BIT) make_executable((uint64_t)(uintptr_t)p, length);
    return p;
}

int munmap(void *addr, size_t length) {
    (void)length;
    free(addr);
    return 0;
}

int mprotect(void *addr, size_t len, int prot) {
    if (prot & PROT_EXEC_BIT) make_executable((uint64_t)(uintptr_t)addr, len);
    return 0;
}
