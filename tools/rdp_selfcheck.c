#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../shaders/rdp.c"

#define RDRAM_WORDS (0x800000 / 4)
#define HIDDEN_BYTES (0x800000 / 2)

static u32 rng_state;
static u32 rng(void)
{
    rng_state = rng_state * 1664525u + 1013904223u;
    return rng_state >> 1;
}

static void ref_fill(u32 *rdram, u8 *hidden, int kind, int x0, int y0, int x1, int y1, int width, u32 image, u32 fc)
{
    for (int y = y0; y <= y1; y++) {
        for (int x = x0; x <= x1; x++) {
            if (kind == KIND_FILL16) {
                u32 value = (x & 1) ? (fc & 0xFFFF) : ((fc >> 16) & 0xFFFF);
                u32 at = (image + (y * width + x) * 2) & RDRAM_MASK;
                u32 index = at >> 2;
                u32 shift = 16u - ((at & 2u) << 3);
                rdram[index] = (rdram[index] & ~(0xFFFFu << shift)) | ((value & 0xFFFFu) << shift);
                hidden[at >> 1] = (value & 1u) ? 3u : 0u;
            } else {
                rdram[((image + (y * width + x) * 4) & RDRAM_MASK) >> 2] = fc;
            }
        }
    }
}

struct Prim { int kind, x0, y0, x1, y1, width; u32 image, fc; };

static int run_case(u32 seed, int kind, int primCount)
{
    static u32 gpuRdram[RDRAM_WORDS], refRdram[RDRAM_WORDS];
    static u8 gpuHidden[HIDDEN_BYTES], refHidden[HIDDEN_BYTES];
    static u32 prims[4096 * PRIM_STRIDE];
    struct Prim recs[4096];

    memset(gpuRdram, 0, sizeof(gpuRdram));
    memset(refRdram, 0, sizeof(refRdram));
    memset(gpuHidden, 0, sizeof(gpuHidden));
    memset(refHidden, 0, sizeof(refHidden));

    rng_state = seed;
    const int width = 320;
    const u32 image = 0x100000;
    int bx0 = 1 << 30, by0 = 1 << 30, bx1 = 0, by1 = 0;

    for (int i = 0; i < primCount; i++) {
        int x0 = rng() % width;
        int x1 = x0 + rng() % (width - x0);
        int y0 = rng() % 240;
        int y1 = y0 + rng() % (240 - y0);
        u32 fc = rng() | (rng() << 1);
        recs[i] = (struct Prim){kind, x0, y0, x1, y1, width, image, fc};

        u32 *rec = prims + i * PRIM_STRIDE;
        rec[PRIM_KIND] = kind;
        rec[PRIM_X0] = x0; rec[PRIM_Y0] = y0; rec[PRIM_X1] = x1; rec[PRIM_Y1] = y1;
        rec[PRIM_WIDTH] = width; rec[PRIM_IMAGE] = image; rec[PRIM_ARG0] = fc;

        if (x0 < bx0) bx0 = x0;
        if (y0 < by0) by0 = y0;
        if (x1 > bx1) bx1 = x1;
        if (y1 > by1) by1 = y1;
    }

    int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
    for (int gid = 0; gid < bw * bh; gid++)
        rdp_core(gid, gpuRdram, gpuHidden, prims, primCount, bx0, by0, bw, bh);

    for (int i = 0; i < primCount; i++)
        ref_fill(refRdram, refHidden, recs[i].kind, recs[i].x0, recs[i].y0, recs[i].x1, recs[i].y1,
                 recs[i].width, recs[i].image, recs[i].fc);

    for (int i = 0; i < RDRAM_WORDS; i++) {
        if (gpuRdram[i] != refRdram[i]) {
            printf("FAIL kind=%d seed=%u rdram[%d] gpu=%08x ref=%08x\n", kind, seed, i, gpuRdram[i], refRdram[i]);
            return 1;
        }
    }
    for (int i = 0; i < HIDDEN_BYTES; i++) {
        if (gpuHidden[i] != refHidden[i]) {
            printf("FAIL kind=%d seed=%u hidden[%d] gpu=%d ref=%d\n", kind, seed, i, gpuHidden[i], refHidden[i]);
            return 1;
        }
    }
    return 0;
}

int main(void)
{
    int fails = 0;
    for (u32 seed = 1; seed <= 40; seed++) {
        fails += run_case(seed, KIND_FILL16, 150);
        fails += run_case(seed + 1000, KIND_FILL32, 150);
    }
    if (fails) {
        printf("rdp_selfcheck: %d FAILURES\n", fails);
        return 1;
    }
    printf("rdp_selfcheck: OK (80 cases, FILL16 short-store + FILL32 dword-store bit-exact)\n");
    return 0;
}
