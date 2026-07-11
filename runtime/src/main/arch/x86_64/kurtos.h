#ifndef KURTOS_H
#define KURTOS_H

#include <stddef.h>
#include <stdint.h>

#define KURTOS_MAX_MEMMAP 64

typedef struct {
    uint64_t base;
    uint64_t length;
    uint64_t type;
} kurtos_memmap_entry_t;

typedef struct {
    uint64_t hhdm_offset;

    uint64_t fb_address;
    uint64_t fb_width;
    uint64_t fb_height;
    uint64_t fb_pitch;
    uint32_t fb_bpp;
    uint32_t fb_present;
    uint32_t fb_red_shift;
    uint32_t fb_green_shift;
    uint32_t fb_blue_shift;

    uint64_t module_address;
    uint64_t module_size;

    uint64_t rsdp_address;

    uint64_t heap_start;
    uint64_t heap_end;
    uint64_t pages_start;
    uint64_t pages_end;

    uint64_t memmap_count;
    kurtos_memmap_entry_t memmap[KURTOS_MAX_MEMMAP];
} kurtos_boot_info_t;

extern kurtos_boot_info_t kurtos_boot_info;

extern uint64_t kurtos_lapic_base;
extern uint64_t kurtos_ticks;

void debug_print(const char *msg);
__attribute__((noreturn)) void hcf(void);

void heap_init(uint64_t base, uint64_t size);

int      kbd_ring_pop(void);
uint64_t kbd_ring_overflows(void);

void lgdt_load(void *ptr, uint16_t code_sel, uint16_t data_sel);
void ltr_load(uint16_t sel);
void lidt_load(void *ptr);

#endif
