typedef unsigned int u32;
typedef unsigned long u64;
typedef unsigned short u16;
typedef unsigned char u8;

#define RDRAM_MASK 0x7FFFFF

#define PRIM_STRIDE 12
#define PRIM_KIND 0
#define PRIM_X0 1
#define PRIM_Y0 2
#define PRIM_X1 3
#define PRIM_Y1 4
#define PRIM_WIDTH 5
#define PRIM_IMAGE 6
#define PRIM_ARG0 7

#define KIND_FILL16 0
#define KIND_FILL32 1

static void store16(u32 *rdram, u8 *hidden, u32 byteAddr, u32 value)
{
    u32 masked = byteAddr & RDRAM_MASK;
    ((u16 *)rdram)[(masked >> 1) ^ 1u] = (u16)value;
    hidden[masked >> 1] = (value & 1u) ? 3u : 0u;
}

static void store32(u32 *rdram, u32 byteAddr, u32 value)
{
    rdram[(byteAddr & RDRAM_MASK) >> 2] = value;
}

static void rdp_core(
    u32 gid,
    u32 *rdram,
    u8 *hidden,
    u32 *prims,
    u32 primCount,
    u32 bboxX,
    u32 bboxY,
    u32 bboxW,
    u32 bboxH)
{
    if (gid >= bboxW * bboxH) return;

    u32 px = bboxX + gid % bboxW;
    u32 py = bboxY + gid / bboxW;

    for (u32 p = 0; p < primCount; p++) {
        u32 *rec = prims + p * PRIM_STRIDE;
        if (px < rec[PRIM_X0] || px > rec[PRIM_X1]) continue;
        if (py < rec[PRIM_Y0] || py > rec[PRIM_Y1]) continue;

        u32 width = rec[PRIM_WIDTH];
        u32 image = rec[PRIM_IMAGE];
        u32 fillColor = rec[PRIM_ARG0];

        if (rec[PRIM_KIND] == KIND_FILL16) {
            u32 value = (px & 1u) ? (fillColor & 0xFFFFu) : ((fillColor >> 16) & 0xFFFFu);
            store16(rdram, hidden, image + (py * width + px) * 2u, value);
        } else {
            store32(rdram, image + (py * width + px) * 4u, fillColor);
        }
    }
}

#ifdef __AMDGCN__
__attribute__((amdgpu_kernel))
void rdp(
    u32 *rdram,
    u8 *hidden,
    u32 *prims,
    u32 primCount,
    u32 bboxX,
    u32 bboxY,
    u32 bboxW,
    u32 bboxH)
{
    u32 gid = __builtin_amdgcn_workgroup_id_x() * 64u + __builtin_amdgcn_workitem_id_x();
    rdp_core(gid, rdram, hidden, prims, primCount, bboxX, bboxY, bboxW, bboxH);
}
#endif
