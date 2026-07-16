#include "kurtos.h"

#define VECTOR_TIMER    0x20
#define VECTOR_KEYBOARD 0x21
#define VECTOR_USB      0x22
#define VECTOR_SPURIOUS 0xFF

#define RING_CAPACITY 256
#define RING_MASK (RING_CAPACITY - 1)

extern void kernel_panic(uint64_t frame);

static volatile uint32_t ring_head;
static volatile uint32_t ring_tail;
static volatile uint8_t  ring_buf[RING_CAPACITY];
static volatile uint64_t ring_dropped;
static volatile uint64_t kbd_events;

static volatile uint64_t usb_events;

uint64_t usb_irq_count(void) {
    return usb_events;
}

uint64_t kbd_irq_count(void) {
    return kbd_events;
}

int kbd_ring_pop(void) {
    uint32_t head = ring_head;
    if (head == ring_tail) return -1;

    uint8_t value = ring_buf[head];
    ring_head = (head + 1) & RING_MASK;
    return (int)value;
}

uint64_t kbd_ring_overflows(void) {
    return ring_dropped;
}

static inline uint8_t inb(uint16_t port) {
    uint8_t value;
    __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port));
    return value;
}

static void eoi(void) {
    if (kurtos_lapic_base == 0) return;
    *(volatile uint32_t *)(kurtos_lapic_base + 0xB0) = 0;
}

static void kbd_push(uint8_t scancode) {
    uint32_t tail = ring_tail;
    uint32_t next = (tail + 1) & RING_MASK;
    if (next == ring_head) {
        ring_dropped++;
    } else {
        ring_buf[tail] = scancode;
        ring_tail = next;
    }
}

static void kbd_poll(void) {
    for (int i = 0; i < 4; i++) {
        uint8_t status = inb(0x64);
        if (!(status & 0x01)) break;
        uint8_t data = inb(0x60);
        if (!(status & 0x20)) kbd_push(data);
    }
}

static int panicking;

void isr_dispatch(uint64_t *frame) {
    uint64_t vector = frame[15];

    if (vector < 32) {
        if (!panicking) {
            panicking = 1;
            kernel_panic((uint64_t)frame);
        }
        debug_print("\nfault inside panic handler\n");
        hcf();
    }

    switch (vector) {
        case VECTOR_TIMER:
            kurtos_ticks++;
            if (kurtos_kbd_poll) kbd_poll();
            break;

        case VECTOR_KEYBOARD: {
            uint8_t status = inb(0x64);
            kbd_events++;
            if (!(status & 0x01)) break;
            uint8_t scancode = inb(0x60);
            if (status & 0x20) break;
            kbd_push(scancode);
            break;
        }

        case VECTOR_USB:
            usb_events++;
            break;

        case VECTOR_SPURIOUS:
            return;

        default:
            break;
    }

    eoi();
}
