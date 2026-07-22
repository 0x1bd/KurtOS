typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
typedef int i32;

#define RDRAM_MASK 0x7FFFFF

#define SPAN_STRIDE 45
#define SPAN_TMEM 44
#define TMEM_BYTES 4096
#define SPAN_ROW 0
#define SPAN_LX 1
#define SPAN_RX 2
#define SPAN_UNSCRX 3
#define SPAN_R 4
#define SPAN_G 5
#define SPAN_B 6
#define SPAN_A 7
#define SPAN_Z 8
#define SPAN_MINORX0 9
#define SPAN_MAJORX0 13
#define SPAN_INVALY0 17
#define SPAN_S 21
#define SPAN_T 22
#define SPAN_W 23
#define SPAN_FLIP 24
#define SPAN_DR 25
#define SPAN_DG 26
#define SPAN_DB 27
#define SPAN_DA 28
#define SPAN_DZ 29
#define SPAN_DS 30
#define SPAN_DT 31
#define SPAN_DW 32
#define SPAN_DZPIX 33
#define SPAN_DZPIXENC 34
#define SPAN_TEXRECT 35
#define SPAN_TR_X0 36
#define SPAN_TR_Y0 37
#define SPAN_TR_S 38
#define SPAN_TR_T 39
#define SPAN_TR_DSDX 40
#define SPAN_TR_DTDY 41
#define SPAN_TR_SHIFT 42
#define SPAN_TR_FLIP 43

#define U_FLIP 0
#define U_SCISSOR_XH 1
#define U_SCISSOR_XL 2
#define U_COLORSIZE 3
#define U_COLORIMAGE 4
#define U_COLORWIDTH 5
#define U_SPANS_DR 6
#define U_SPANS_DG 7
#define U_SPANS_DB 8
#define U_SPANS_DA 9
#define U_PRIM 10
#define U_ENV 11
#define U_BLEND 12
#define U_FOG 13
#define U_PRIMLOD 14
#define U_CRGB 15
#define U_CALPHA 23
#define U_BLENDSEL 31
#define U_SPANS_DZ 39
#define U_ZIMAGE 40
#define U_ZMODE 41
#define U_ZCOMPARE 42
#define U_ZUPDATE 43
#define U_ZSOURCE 44
#define U_ALPHACOMPARE 45
#define U_CTA 46
#define U_ACS 47
#define U_FORCEBLEND 48
#define U_PRIMDEPTH 49
#define U_PRIMDZ 50
#define U_PRIMDZENC 51
#define U_SPANDZPIX 52
#define U_SPANDZPIXENC 53
#define U_USEDEPTH 54
#define U_SHADE 55
#define U_TEXTURE 56
#define U_PERSP 57
#define U_SAMPLETYPE 58
#define U_TLUTEN 59
#define U_TLUTTYPE 60
#define U_MIDTEXEL 61
#define U_SPANS_DS 62
#define U_SPANS_DT 63
#define U_SPANS_DW 64
#define T_FORMAT 65
#define T_SIZE 66
#define T_LINE 67
#define T_TMEM 68
#define T_PALETTE 69
#define T_MIRRORS 71
#define T_MASKS 72
#define T_SHIFTS 73
#define T_MIRRORT 75
#define T_MASKT 76
#define T_SHIFTT 77
#define T_SL 78
#define T_TL 79
#define T_SH 80
#define T_TH 81
#define T_CLAMPDIFFS 82
#define T_CLAMPDIFFT 83
#define T_CLAMPENS 84
#define T_CLAMPENT 85
#define T_MASKSCLAMPED 86
#define T_MASKTCLAMPED 87
#define U_CYCLE2 88
#define U_TEXRECT 89
#define U_COPY 90
#define U_TR_X0 91
#define U_TR_Y0 92
#define U_TR_S 93
#define U_TR_T 94
#define U_TR_DSDX 95
#define U_TR_DTDY 96
#define U_TR_SHIFT 97
#define U_TR_FLIP 98
#define U_FILL 99
#define U_FILLCOLOR 100
#define U_RECTSHADE 101

static i32 clamp255(i32 v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
static i32 channel(u32 color, i32 index) { return (i32)((color >> (index * 8)) & 0xFFu); }
static i32 sx16(i32 v) { return (i32)(short)v; }
static i32 maskbits(i32 m) { return m == 0 ? 0x3FF : ((0xFFFF >> (16 - m)) & 0x3FF); }

static u32 pack(i32 r, i32 g, i32 b, i32 a)
{
    return ((u32)r & 0xFFu) | (((u32)g & 0xFFu) << 8) | (((u32)b & 0xFFu) << 16) | (((u32)a & 0xFFu) << 24);
}

static u32 rgba16(i32 value)
{
    i32 r5 = (value >> 11) & 0x1F;
    i32 g5 = (value >> 6) & 0x1F;
    i32 b5 = (value >> 1) & 0x1F;
    i32 a = (value & 1) ? 0xFF : 0;
    return pack((r5 << 3) | (r5 >> 2), (g5 << 3) | (g5 >> 2), (b5 << 3) | (b5 >> 2), a);
}

static i32 rdram16(u32 *__restrict rdram, u32 addr)
{
    u32 masked = addr & RDRAM_MASK;
    return (i32)((u16 *)rdram)[(masked >> 1) ^ 1u];
}

static void store16(u32 *__restrict rdram, u32 addr, i32 value)
{
    u32 masked = addr & RDRAM_MASK;
    ((u16 *)rdram)[(masked >> 1) ^ 1u] = (u16)value;
}

static u32 read_color(u32 *__restrict rdram, const u32 *__restrict u, i32 x, i32 y)
{
    i32 size = (i32)u[U_COLORSIZE];
    u32 image = u[U_COLORIMAGE];
    u32 width = u[U_COLORWIDTH];
    if (size == 2) return rgba16(rdram16(rdram, image + (u32)(y * (i32)width + x) * 2u));
    if (size == 3) {
        u32 v = rdram[((image + (u32)(y * (i32)width + x) * 4u) & RDRAM_MASK) >> 2];
        return pack((i32)(v >> 24) & 0xFF, (i32)(v >> 16) & 0xFF, (i32)(v >> 8) & 0xFF, (i32)v & 0xFF);
    }
    return 0;
}

static void write_color(u32 *__restrict rdram, const u32 *__restrict u, i32 x, i32 y, u32 color)
{
    i32 r = channel(color, 0), g = channel(color, 1), b = channel(color, 2), a = channel(color, 3);
    i32 size = (i32)u[U_COLORSIZE];
    u32 image = u[U_COLORIMAGE];
    u32 width = u[U_COLORWIDTH];
    if (size == 2) {
        i32 value = ((r * 31 / 255) << 11) | ((g * 31 / 255) << 6) | ((b * 31 / 255) << 1) | (a >= 128 ? 1 : 0);
        store16(rdram, image + (u32)(y * (i32)width + x) * 2u, value);
    } else if (size == 3) {
        rdram[((image + (u32)(y * (i32)width + x) * 4u) & RDRAM_MASK) >> 2] = ((u32)r << 24) | ((u32)g << 16) | ((u32)b << 8) | (u32)a;
    }
}

static i32 tmem_byte(const u8 *__restrict tmem, i32 at, i32 row)
{
    i32 offset = at;
    if (row & 1) offset ^= 4;
    return tmem[offset & 0xFFF];
}

static u32 palette(const u32 *__restrict u, const u8 *__restrict tmem, i32 index)
{
    i32 at = 0x800 + (index & 0xFF) * 8;
    i32 value = (tmem[at & 0xFFF] << 8) | tmem[(at + 1) & 0xFFF];
    if ((i32)u[U_TLUTTYPE] == 0) return rgba16(value);
    i32 inten = (value >> 8) & 0xFF;
    return pack(inten, inten, inten, value & 0xFF);
}

static u32 decode4(const u32 *__restrict u, const u8 *__restrict tmem, i32 value)
{
    i32 fmt = (i32)u[T_FORMAT];
    if (fmt == 2) return palette(u, tmem, (i32)u[T_PALETTE] * 16 + value);
    if (fmt == 3) {
        i32 three = (value >> 1) & 7;
        i32 inten = (three << 5) | (three << 2) | (three >> 1);
        return pack(inten, inten, inten, (value & 1) ? 0xFF : 0);
    }
    i32 inten = value * 17;
    return pack(inten, inten, inten, inten);
}

static u32 decode8(const u32 *__restrict u, const u8 *__restrict tmem, i32 value)
{
    i32 fmt = (i32)u[T_FORMAT];
    if (fmt == 2) return palette(u, tmem, value);
    if (fmt == 3) {
        i32 inten = ((value >> 4) & 0xF) * 17;
        i32 a = (value & 0xF) * 17;
        return pack(inten, inten, inten, a);
    }
    return pack(value, value, value, value);
}

static u32 decode16(const u32 *__restrict u, const u8 *__restrict tmem, i32 value)
{
    i32 fmt = (i32)u[T_FORMAT];
    if (fmt == 3) {
        i32 inten = (value >> 8) & 0xFF;
        return pack(inten, inten, inten, value & 0xFF);
    }
    if (fmt == 2) return palette(u, tmem, value & 0xFF);
    return rgba16(value);
}

static u32 fetch(const u32 *__restrict u, const u8 *__restrict tmem, i32 s, i32 t)
{
    i32 base = (i32)u[T_TMEM] * 8 + t * (i32)u[T_LINE] * 8;
    i32 size = (i32)u[T_SIZE];
    if (size == 0) {
        i32 at = base + s / 2;
        i32 byte = tmem_byte(tmem, at, t);
        i32 nib = (s & 1) == 0 ? (byte >> 4) & 0xF : byte & 0xF;
        return decode4(u, tmem, nib);
    }
    if (size == 1) {
        return decode8(u, tmem, tmem_byte(tmem, base + s, t));
    }
    if (size == 2) {
        i32 at = base + s * 2;
        i32 value = (tmem_byte(tmem, at, t) << 8) | tmem_byte(tmem, at + 1, t);
        return decode16(u, tmem, value);
    }
    i32 at = base + s * 2;
    i32 high = at;
    if (t & 1) high ^= 4;
    i32 r = tmem[high & 0x7FF];
    i32 g = tmem[(high + 1) & 0x7FF];
    i32 b = tmem[(high & 0x7FF) | 0x800];
    i32 a = tmem[((high + 1) & 0x7FF) | 0x800];
    return pack(r, g, b, a);
}

static i32 tclod_clamp(i32 coord)
{
    if (coord & 0x40000) return 0x7FFF;
    if (coord & 0x20000) return 0x8000;
    switch (coord & 0x18000) {
    case 0x8000: return 0x7FFF;
    case 0x10000: return 0x8000;
    default: return coord & 0xFFFF;
    }
}

static i32 tc_shift(i32 coord, i32 shifter)
{
    return shifter < 11 ? (sx16(coord) >> shifter) : sx16(coord << (16 - shifter));
}

static void divide(i32 s, i32 t, i32 w, const u32 *__restrict tcdiv, i32 persp, i32 *outS, i32 *outT)
{
    i32 ss = (s >> 16) & 0xFFFF;
    i32 st = (t >> 16) & 0xFFFF;
    i32 sw = (w >> 16) & 0xFFFF;
    i32 sss, sst;
    if (!persp) {
        sss = sx16(ss) & 0x1FFFF;
        sst = sx16(st) & 0x1FFFF;
    } else {
        i32 wCarry = sx16(sw) <= 0;
        i32 wIndex = sw & 0x7FFF;
        i32 entry = (i32)tcdiv[wIndex];
        i32 tluRcp = entry >> 4;
        i32 shift = entry & 0xF;
        i32 sprod = sx16(ss) * tluRcp;
        i32 tprod = sx16(st) * tluRcp;
        i32 tempmask = ((1 << 30) - 1) & -((1 << 29) >> shift);
        i32 oobS = sprod & tempmask;
        i32 oobT = tprod & tempmask;
        i32 temps, tempt;
        if (shift != 0xE) {
            temps = sprod >> (13 - shift);
            tempt = tprod >> (13 - shift);
        } else {
            temps = sprod << 1;
            tempt = tprod << 1;
        }
        i32 ouS = 0, ouT = 0;
        if (oobS != tempmask && oobS != 0) ouS = (sprod & (1 << 29)) == 0 ? (2 << 17) : (1 << 17);
        if (oobT != tempmask && oobT != 0) ouT = (tprod & (1 << 29)) == 0 ? (2 << 17) : (1 << 17);
        if (wCarry) {
            ouS |= (2 << 17);
            ouT |= (2 << 17);
        }
        sss = (temps & 0x1FFFF) | ouS;
        sst = (tempt & 0x1FFFF) | ouT;
    }
    *outS = tclod_clamp(sss);
    *outT = tclod_clamp(sst);
}

static u32 sample_point(const u32 *__restrict u, const u8 *__restrict tmem, i32 rawS, i32 rawT)
{
    i32 s = tc_shift(rawS, (i32)u[T_SHIFTS]);
    i32 maxS = (s >> 3) >= (i32)u[T_SH];
    i32 t = tc_shift(rawT, (i32)u[T_SHIFTT]);
    i32 maxT = (t >> 3) >= (i32)u[T_TH];
    s -= (i32)u[T_SL] << 3;
    t -= (i32)u[T_TL] << 3;
    if ((i32)u[T_CLAMPENS]) s = maxS ? (i32)u[T_CLAMPDIFFS] : ((s & 0x10000) ? 0 : (s >> 5));
    else s >>= 5;
    if ((i32)u[T_CLAMPENT]) t = maxT ? (i32)u[T_CLAMPDIFFT] : ((t & 0x10000) ? 0 : (t >> 5));
    else t >>= 5;
    i32 maskS = (i32)u[T_MASKS], maskT = (i32)u[T_MASKT];
    if (maskS) {
        if ((i32)u[T_MIRRORS] && ((s >> (i32)u[T_MASKSCLAMPED]) & 1)) s = ~s;
        s &= maskbits(maskS);
    }
    if (maskT) {
        if ((i32)u[T_MIRRORT] && ((t >> (i32)u[T_MASKTCLAMPED]) & 1)) t = ~t;
        t &= maskbits(maskT);
    }
    return fetch(u, tmem, s, t);
}

static u32 sample_tex(const u32 *__restrict u, const u8 *__restrict tmem, i32 rawS, i32 rawT)
{
    i32 s = tc_shift(rawS, (i32)u[T_SHIFTS]);
    i32 maxS = (s >> 3) >= (i32)u[T_SH];
    i32 t = tc_shift(rawT, (i32)u[T_SHIFTT]);
    i32 maxT = (t >> 3) >= (i32)u[T_TH];
    s -= (i32)u[T_SL] << 3;
    t -= (i32)u[T_TL] << 3;

    i32 clampEnS = (i32)u[T_CLAMPENS], clampEnT = (i32)u[T_CLAMPENT];
    i32 maskS = (i32)u[T_MASKS], maskT = (i32)u[T_MASKT];
    i32 sampleType = (i32)u[U_SAMPLETYPE];

    if (sampleType == 0 && !(i32)u[U_TLUTEN]) {
        if (clampEnS) s = maxS ? (i32)u[T_CLAMPDIFFS] : ((s & 0x10000) ? 0 : (s >> 5));
        else s >>= 5;
        if (clampEnT) t = maxT ? (i32)u[T_CLAMPDIFFT] : ((t & 0x10000) ? 0 : (t >> 5));
        else t >>= 5;
        if (maskS) {
            if ((i32)u[T_MIRRORS] && ((s >> (i32)u[T_MASKSCLAMPED]) & 1)) s = ~s;
            s &= maskbits(maskS);
        }
        if (maskT) {
            if ((i32)u[T_MIRRORT] && ((t >> (i32)u[T_MASKTCLAMPED]) & 1)) t = ~t;
            t &= maskbits(maskT);
        }
        return fetch(u, tmem, s, t);
    }

    i32 sFrac = s & 0x1F, tFrac = t & 0x1F;
    if (clampEnS) {
        if (maxS) { s = (i32)u[T_CLAMPDIFFS]; sFrac = 0; }
        else if (s & 0x10000) { s = 0; sFrac = 0; }
        else s >>= 5;
    } else s >>= 5;
    if (clampEnT) {
        if (maxT) { t = (i32)u[T_CLAMPDIFFT]; tFrac = 0; }
        else if (t & 0x10000) { t = 0; tFrac = 0; }
        else t >>= 5;
    } else t >>= 5;

    i32 sDiff, tDiff;
    if (maskS) {
        i32 bits = maskbits(maskS);
        if ((i32)u[T_MIRRORS]) {
            i32 wrap = (s >> (i32)u[T_MASKSCLAMPED]) & 1;
            if (wrap) s = ~s;
            s &= bits;
            sDiff = (((s - wrap) & bits) == bits) ? 0 : (1 - (wrap << 1));
        } else {
            s &= bits;
            sDiff = (s == bits) ? -s : 1;
        }
    } else sDiff = 1;
    if (maskT) {
        i32 bits = maskbits(maskT);
        if ((i32)u[T_MIRRORT]) {
            i32 wrap = (t >> (i32)u[T_MASKTCLAMPED]) & 1;
            if (wrap) t = ~t;
            t &= bits;
            tDiff = (((t - wrap) & bits) == bits) ? 0 : (1 - (wrap << 1));
        } else {
            t &= bits;
            tDiff = (t == bits) ? -(t & 0xFF) : 1;
        }
    } else tDiff = 1;
    if (sampleType == 0) { sDiff = 0; tDiff = 0; }

    u32 t0 = fetch(u, tmem, s, t);
    u32 t1 = fetch(u, tmem, s + sDiff, t);
    u32 t2 = fetch(u, tmem, s, t + tDiff);
    u32 t3 = fetch(u, tmem, s + sDiff, t + tDiff);

    i32 upper = (sFrac + tFrac) & 0x20;
    i32 center = (i32)u[U_MIDTEXEL] && sFrac == 0x10 && tFrac == 0x10;
    u32 result = 0;
    for (i32 idx = 0; idx < 4; idx++) {
        i32 sh = idx * 8;
        i32 c0 = (i32)(t0 >> sh) & 0xFF, c1 = (i32)(t1 >> sh) & 0xFF, c2 = (i32)(t2 >> sh) & 0xFF, c3 = (i32)(t3 >> sh) & 0xFF;
        i32 value;
        if (center) {
            value = c3 + ((((c1 + c2) << 6) - (c3 << 7) + ((~c3 + c0) << 6) + 0xC0) >> 8);
        } else if (upper) {
            i32 invSf = 0x20 - sFrac, invTf = 0x20 - tFrac;
            value = c3 + ((invSf * (c2 - c3) + invTf * (c1 - c3) + 0x10) >> 5);
        } else {
            value = c0 + ((sFrac * (c1 - c0) + tFrac * (c2 - c0) + 0x10) >> 5);
        }
        result |= (u32)clamp255(value) << sh;
    }
    return result;
}

static i32 csrc(i32 s, i32 index, u32 texel0, u32 texel1, u32 shade, u32 combined, const u32 *__restrict u, i32 isA)
{
    switch (s) {
    case 0: return channel(combined, index);
    case 1: return channel(texel0, index);
    case 2: return channel(texel1, index);
    case 3: return channel(u[U_PRIM], index);
    case 4: return channel(shade, index);
    case 5: return channel(u[U_ENV], index);
    case 6: return isA ? 0xFF : 0;
    default: return 0;
    }
}

static i32 cmul(i32 s, i32 index, u32 texel0, u32 texel1, u32 shade, i32 shadeAlpha, u32 combined, i32 combinedAlpha, const u32 *__restrict u)
{
    switch (s) {
    case 0: return channel(combined, index);
    case 1: return channel(texel0, index);
    case 2: return channel(texel1, index);
    case 3: return channel(u[U_PRIM], index);
    case 4: return channel(shade, index);
    case 5: return channel(u[U_ENV], index);
    case 6: return 0xFF;
    case 7: return combinedAlpha;
    case 8: return (i32)(texel0 >> 24) & 0xFF;
    case 9: return (i32)(texel1 >> 24) & 0xFF;
    case 10: return (i32)(u[U_PRIM] >> 24) & 0xFF;
    case 11: return shadeAlpha;
    case 12: return (i32)(u[U_ENV] >> 24) & 0xFF;
    case 14: return (i32)u[U_PRIMLOD];
    default: return 0;
    }
}

static i32 cadd(i32 s, i32 index, u32 texel0, u32 texel1, u32 shade, u32 combined, const u32 *__restrict u)
{
    switch (s) {
    case 0: return channel(combined, index);
    case 1: return channel(texel0, index);
    case 2: return channel(texel1, index);
    case 3: return channel(u[U_PRIM], index);
    case 4: return channel(shade, index);
    case 5: return channel(u[U_ENV], index);
    case 6: return 0xFF;
    default: return 0;
    }
}

static i32 asrc(i32 s, u32 texel0, u32 texel1, i32 shadeAlpha, i32 combinedAlpha, const u32 *__restrict u)
{
    switch (s) {
    case 0: return combinedAlpha;
    case 1: return (i32)(texel0 >> 24) & 0xFF;
    case 2: return (i32)(texel1 >> 24) & 0xFF;
    case 3: return (i32)(u[U_PRIM] >> 24) & 0xFF;
    case 4: return shadeAlpha;
    case 5: return (i32)(u[U_ENV] >> 24) & 0xFF;
    case 6: return 0xFF;
    default: return 0;
    }
}

static i32 amul(i32 s, u32 texel0, u32 texel1, i32 shadeAlpha, const u32 *__restrict u)
{
    switch (s) {
    case 1: return (i32)(texel0 >> 24) & 0xFF;
    case 2: return (i32)(texel1 >> 24) & 0xFF;
    case 3: return (i32)(u[U_PRIM] >> 24) & 0xFF;
    case 4: return shadeAlpha;
    case 5: return (i32)(u[U_ENV] >> 24) & 0xFF;
    case 6: return (i32)u[U_PRIMLOD];
    default: return 0;
    }
}

static u32 combine(u32 texel0, u32 texel1, u32 shade, i32 shadeAlpha, const u32 *__restrict u)
{
    u32 combined = 0;
    i32 combinedAlpha = 0;
    i32 cycles = (i32)u[U_CYCLE2] ? 2 : 1;
    for (i32 cycle = 0; cycle < cycles; cycle++) {
        i32 result = 0;
        for (i32 index = 0; index < 3; index++) {
            i32 a = csrc((i32)u[U_CRGB + 0 + cycle], index, texel0, texel1, shade, combined, u, 1);
            i32 b = csrc((i32)u[U_CRGB + 2 + cycle], index, texel0, texel1, shade, combined, u, 0);
            i32 c = cmul((i32)u[U_CRGB + 4 + cycle], index, texel0, texel1, shade, shadeAlpha, combined, combinedAlpha, u);
            i32 d = cadd((i32)u[U_CRGB + 6 + cycle], index, texel0, texel1, shade, combined, u);
            result |= clamp255((((a - b) * c) >> 8) + d) << (index * 8);
        }
        i32 aa = asrc((i32)u[U_CALPHA + 0 + cycle], texel0, texel1, shadeAlpha, combinedAlpha, u);
        i32 ab = asrc((i32)u[U_CALPHA + 2 + cycle], texel0, texel1, shadeAlpha, combinedAlpha, u);
        i32 ac = amul((i32)u[U_CALPHA + 4 + cycle], texel0, texel1, shadeAlpha, u);
        i32 ad = asrc((i32)u[U_CALPHA + 6 + cycle], texel0, texel1, shadeAlpha, combinedAlpha, u);
        combinedAlpha = clamp255((((aa - ab) * ac) >> 8) + ad);
        combined = (u32)result;
    }
    return (combined & 0xFFFFFFu) | (((u32)combinedAlpha & 0xFFu) << 24);
}

static u32 blend_select(i32 code, u32 chained, u32 *__restrict rdram, const u32 *__restrict u, i32 x, i32 y)
{
    switch (code) {
    case 0: return chained;
    case 1: return read_color(rdram, u, x, y);
    case 2: return u[U_BLEND];
    default: return u[U_FOG];
    }
}

static u32 blend_equation(i32 cycle, u32 chained, i32 pixelAlpha, u32 *__restrict rdram, const u32 *__restrict u, i32 x, i32 y, i32 shadeAlpha)
{
    i32 bA = (i32)u[U_BLENDSEL + 0 + cycle], bB = (i32)u[U_BLENDSEL + 2 + cycle], bC = (i32)u[U_BLENDSEL + 4 + cycle], bD = (i32)u[U_BLENDSEL + 6 + cycle];
    i32 aMul = bB == 0 ? pixelAlpha : (bB == 1 ? ((i32)(u[U_FOG] >> 24) & 0xFF) : (bB == 2 ? shadeAlpha : 0));
    i32 bMul = bD == 0 ? ((~aMul) & 0xFF) : (bD == 1 ? 0xE0 : (bD == 2 ? 0xFF : 0));
    i32 blend1a = aMul >> 3;
    i32 blend2a = bMul >> 3;
    if (bD == 1) blend2a |= 3;
    i32 mulb = blend2a + 1;
    u32 p = blend_select(bA, chained, rdram, u, x, y);
    u32 m = blend_select(bC, chained, rdram, u, x, y);
    u32 result = 0;
    for (i32 idx = 0; idx < 3; idx++) {
        i32 value = channel(p, idx) * blend1a + channel(m, idx) * mulb;
        result |= (u32)((value >> 5) & 0xFF) << (idx * 8);
    }
    return result;
}

static u32 blend_pixel(u32 *__restrict rdram, const u32 *__restrict u, i32 x, i32 y, u32 color, i32 shadeAlpha)
{
    i32 alpha = (i32)(color >> 24) & 0xFF;
    if (alpha == 0xFF) alpha = 0x100;
    u32 chained = color;
    if ((i32)u[U_CYCLE2]) {
        chained = (blend_equation(0, color, alpha, rdram, u, x, y, shadeAlpha) & 0xFFFFFFu) | (color & 0xFF000000u);
    }
    i32 cycle = (i32)u[U_CYCLE2] ? 1 : 0;
    i32 bB = (i32)u[U_BLENDSEL + 2 + cycle], bD = (i32)u[U_BLENDSEL + 6 + cycle];
    i32 partialReject = (bB == 0) && (bD == 0);
    u32 result;
    if (!(i32)u[U_FORCEBLEND] || (partialReject && alpha >= 0xFF))
        result = blend_select((i32)u[U_BLENDSEL + 0 + cycle], chained, rdram, u, x, y);
    else
        result = blend_equation(cycle, chained, alpha, rdram, u, x, y, shadeAlpha);
    return (result & 0xFFFFFFu) | (((color >> 24) & 0xFFu) << 24);
}

static i32 zcorrect(i32 walked)
{
    i32 sz = ((walked >> 10) & 0x3FFFFF) >> 3;
    switch ((sz & 0x60000) >> 17) {
    case 2: return 0x3FFFF;
    case 3: return 0;
    default: return sz & 0x3FFFF;
    }
}

static i32 zpass(u32 *__restrict rdram, u8 *__restrict hidden, const u32 *__restrict zdec, const u32 *__restrict deltaz, const u32 *__restrict u, i32 x, i32 y, i32 szIn, i32 dzPix)
{
    i32 sz = szIn & 0x3FFFF;
    u32 at = u[U_ZIMAGE] + (u32)(y * (i32)u[U_COLORWIDTH] + x) * 2u;
    i32 zval = rdram16(rdram, at);
    i32 hval = hidden[(at & RDRAM_MASK) >> 1] & 3;

    i32 oz = (i32)zdec[(zval >> 2) & 0x3FFF];
    i32 rawDzMem = ((zval & 3) << 2) | hval;
    i32 dzMem = 1 << rawDzMem;
    i32 forceCoplanar = 0;

    i32 precision = (zval >> 13) & 0xF;
    if (precision < 3) {
        if (dzMem != 0x8000) {
            i32 modifier = 16 >> precision;
            dzMem <<= 1;
            if (dzMem < modifier) dzMem = modifier;
        } else {
            forceCoplanar = 1;
            dzMem = 0xFFFF;
        }
    }

    i32 dzNew = (i32)deltaz[(dzPix | dzMem) & 0xFFFF] << 3;
    i32 farther = forceCoplanar || (sz + dzNew >= oz);
    i32 max = oz == 0x3FFFF;
    i32 inFront = sz < oz;

    switch ((i32)u[U_ZMODE]) {
    case 0: return max || inFront;
    case 1: return (!inFront || !farther) ? (max || inFront) : 1;
    case 2: return inFront || max;
    default: {
        i32 nearer = forceCoplanar || (sz - dzNew <= oz);
        return farther && nearer && !max;
    }
    }
}

static void zstore(u32 *__restrict rdram, u8 *__restrict hidden, const u32 *__restrict zcom, const u32 *__restrict u, i32 x, i32 y, i32 z, i32 dzPixEnc)
{
    u32 at = u[U_ZIMAGE] + (u32)(y * (i32)u[U_COLORWIDTH] + x) * 2u;
    store16(rdram, at, (i32)zcom[z & 0x3FFFF] | (dzPixEnc >> 2));
    hidden[(at & RDRAM_MASK) >> 1] = (u8)(dzPixEnc & 3);
}

static i32 rightcvghex(i32 x, i32 fmask)
{
    i32 covered = ((x & 7) + 1) >> 1;
    return (0xF0 >> covered) & fmask;
}

static i32 leftcvghex(i32 x, i32 fmask)
{
    i32 covered = ((x & 7) + 1) >> 1;
    return (0xF >> covered) & fmask;
}

static i32 popcount8(i32 v)
{
    i32 x = v & 0xFF;
    x -= (x >> 1) & 0x55;
    x = (x & 0x33) + ((x >> 2) & 0x33);
    return (x + (x >> 4)) & 0xF;
}

static i32 coverage_at(i32 x, i32 flip, i32 ps, i32 pe, const u32 *__restrict rec)
{
    i32 mask = 0xFF;
    for (i32 sub = 0; sub < 4; sub++) {
        i32 fmask = 0xA >> (sub & 1);
        i32 maskshift = (sub - 2) & 4;
        i32 fms = fmask << maskshift;
        if ((i32)rec[SPAN_INVALY0 + sub] != 0) {
            mask &= ~fms;
            continue;
        }
        i32 minorcur = (i32)rec[SPAN_MINORX0 + sub];
        i32 majorcur = (i32)rec[SPAN_MAJORX0 + sub];
        i32 minorint = minorcur >> 3;
        i32 majorint = majorcur >> 3;
        if (flip) {
            i32 lo = majorint < pe ? majorint : pe;
            i32 hi = minorint > ps ? minorint : ps;
            if (x <= lo || x >= hi) mask &= ~fms;
            if (minorint > majorint) {
                if (x == minorint) mask |= rightcvghex(minorcur, fmask) << maskshift;
                if (x == majorint) mask |= leftcvghex(majorcur, fmask) << maskshift;
            } else if (minorint == majorint) {
                if (x == majorint) mask |= (rightcvghex(minorcur, fmask) & leftcvghex(majorcur, fmask)) << maskshift;
            }
        } else {
            i32 lo = minorint < pe ? minorint : pe;
            i32 hi = majorint > ps ? majorint : ps;
            if (x <= lo || x >= hi) mask &= ~fms;
            if (majorint > minorint) {
                if (x == minorint) mask |= leftcvghex(minorcur, fmask) << maskshift;
                if (x == majorint) mask |= rightcvghex(majorcur, fmask) << maskshift;
            } else if (minorint == majorint) {
                if (x == majorint) mask |= (leftcvghex(minorcur, fmask) & rightcvghex(majorcur, fmask)) << maskshift;
            }
        }
    }
    return mask;
}

static void write_pixel(u32 *__restrict rdram, u8 *__restrict hidden, const u32 *__restrict zcom, const u32 *__restrict zdec, const u32 *__restrict deltaz, const u32 *__restrict u, i32 x, i32 y, u32 color, i32 z, i32 shadeAlpha, i32 cvg, i32 spanDzPix, i32 spanDzEnc)
{
    i32 alpha = (i32)(color >> 24) & 0xFF;
    if ((i32)u[U_CTA]) {
        i32 scaled = (alpha * cvg + 4) >> 3;
        if ((scaled >> 5) == 0) return;
        if ((i32)u[U_ACS]) alpha = scaled < 0xFF ? scaled : 0xFF;
    } else if ((i32)u[U_ACS]) {
        alpha = cvg >= 8 ? 0xFF : (cvg << 5);
    }
    if ((i32)u[U_ALPHACOMPARE]) {
        i32 ref = (i32)(u[U_BLEND] >> 24) & 0xFF;
        if (alpha < ref) return;
    }
    u32 recolor = (color & 0xFFFFFFu) | ((u32)alpha << 24);
    if ((i32)u[U_USEDEPTH] && u[U_ZIMAGE] != 0) {
        i32 dzPix = (i32)u[U_ZSOURCE] ? (i32)u[U_PRIMDZ] : spanDzPix;
        i32 dzEnc = (i32)u[U_ZSOURCE] ? (i32)u[U_PRIMDZENC] : spanDzEnc;
        if ((i32)u[U_ZCOMPARE] && !zpass(rdram, hidden, zdec, deltaz, u, x, y, z, dzPix)) return;
        write_color(rdram, u, x, y, blend_pixel(rdram, u, x, y, recolor, shadeAlpha));
        if ((i32)u[U_ZUPDATE]) zstore(rdram, hidden, zcom, u, x, y, z, dzEnc);
        return;
    }
    write_color(rdram, u, x, y, blend_pixel(rdram, u, x, y, recolor, shadeAlpha));
}

static void span_fill_at(const u32 *__restrict rec, const u32 *__restrict u, i32 x, u32 *__restrict rdram, u8 *__restrict hidden)
{
    i32 xstart = (i32)rec[SPAN_LX];
    i32 xendsc = (i32)rec[SPAN_RX];
    if (x < xstart || x > xendsc) return;

    i32 row = (i32)rec[SPAN_ROW];
    u32 fillColor = u[U_FILLCOLOR];
    i32 size = (i32)u[U_COLORSIZE];
    u32 image = u[U_COLORIMAGE];
    i32 width = (i32)u[U_COLORWIDTH];
    if (size == 2) {
        i32 value = (x & 1) == 0 ? (i32)((fillColor >> 16) & 0xFFFFu) : (i32)(fillColor & 0xFFFFu);
        u32 at = image + (u32)(row * width + x) * 2u;
        store16(rdram, at, value);
        hidden[(at & RDRAM_MASK) >> 1] = (u8)((value & 1) ? 3 : 0);
    } else if (size == 3) {
        rdram[((image + (u32)(row * width + x) * 4u) & RDRAM_MASK) >> 2] = fillColor;
    }
}

typedef struct {
    i32 row, xendsc, xinc, length;
    i32 scXh, scXl;
    i32 r0, g0, b0, a0, z0, s0, t0, w0;
    i32 drinc, dginc, dbinc, dainc, dzinc, dsinc, dtinc, dwinc;
    i32 flip, ps, pe;
    i32 spanDzPix, spanDzEnc;
    i32 ownerMode, needCvg, shadeOn, textureOn, persp;
    i32 texrect, copy;
    i32 trX0, trY0, trS, trT, trDsdx, trDtdy, trShift, trFlip;
} span_ctx;

__attribute__((always_inline))
static inline span_ctx span_setup(const u32 *__restrict rec, const u32 *__restrict u)
{
    span_ctx c;
    i32 row = (i32)rec[SPAN_ROW];
    i32 xstart = (i32)rec[SPAN_LX];
    i32 xendsc = (i32)rec[SPAN_RX];
    i32 xend = (i32)rec[SPAN_UNSCRX];

    i32 r0 = (i32)rec[SPAN_R];
    i32 g0 = (i32)rec[SPAN_G];
    i32 b0 = (i32)rec[SPAN_B];
    i32 a0 = (i32)rec[SPAN_A];
    i32 z0 = (i32)rec[SPAN_Z];
    i32 s0 = (i32)rec[SPAN_S];
    i32 t0 = (i32)rec[SPAN_T];
    i32 w0 = (i32)rec[SPAN_W];

    i32 flip = (i32)rec[SPAN_FLIP];
    i32 spanDzPix = (i32)rec[SPAN_DZPIX];
    i32 spanDzEnc = (i32)rec[SPAN_DZPIXENC];
    i32 scXh = (i32)u[U_SCISSOR_XH];
    i32 scXl = (i32)u[U_SCISSOR_XL];
    i32 drinc = flip ? (i32)rec[SPAN_DR] : -(i32)rec[SPAN_DR];
    i32 dginc = flip ? (i32)rec[SPAN_DG] : -(i32)rec[SPAN_DG];
    i32 dbinc = flip ? (i32)rec[SPAN_DB] : -(i32)rec[SPAN_DB];
    i32 dainc = flip ? (i32)rec[SPAN_DA] : -(i32)rec[SPAN_DA];
    i32 dzinc = flip ? (i32)rec[SPAN_DZ] : -(i32)rec[SPAN_DZ];
    i32 dsinc = flip ? (i32)rec[SPAN_DS] : -(i32)rec[SPAN_DS];
    i32 dtinc = flip ? (i32)rec[SPAN_DT] : -(i32)rec[SPAN_DT];
    i32 dwinc = flip ? (i32)rec[SPAN_DW] : -(i32)rec[SPAN_DW];
    i32 xinc = flip ? 1 : -1;

    i32 length, scdiff;
    if (flip) {
        length = xstart - xendsc;
        scdiff = xendsc - xend;
    } else {
        length = xendsc - xstart;
        scdiff = xend - xendsc;
    }
    if (scdiff != 0) {
        scdiff &= 0xFFF;
        r0 += drinc * scdiff;
        g0 += dginc * scdiff;
        b0 += dbinc * scdiff;
        a0 += dainc * scdiff;
        s0 += dsinc * scdiff;
        t0 += dtinc * scdiff;
        w0 += dwinc * scdiff;
        z0 += dzinc * scdiff;
    }

    i32 forceBlend = (i32)u[U_FORCEBLEND];
    i32 cta = (i32)u[U_CTA];
    i32 acs = (i32)u[U_ACS];
    i32 ownerMode = forceBlend && !cta && !acs;
    i32 needCvg = cta || acs || ownerMode;
    i32 shadeOn = (i32)u[U_SHADE];
    i32 textureOn = (i32)u[U_TEXTURE];
    i32 persp = (i32)u[U_PERSP];
    i32 ps = flip ? xendsc : xstart;
    i32 pe = flip ? xstart : xendsc;

    i32 texrect = (i32)rec[SPAN_TEXRECT];
    i32 copy = (i32)u[U_COPY];
    i32 trX0 = (i32)rec[SPAN_TR_X0], trY0 = (i32)rec[SPAN_TR_Y0];
    i32 trS = (i32)rec[SPAN_TR_S], trT = (i32)rec[SPAN_TR_T];
    i32 trDsdx = (i32)rec[SPAN_TR_DSDX], trDtdy = (i32)rec[SPAN_TR_DTDY];
    i32 trShift = (i32)rec[SPAN_TR_SHIFT], trFlip = (i32)rec[SPAN_TR_FLIP];

    c.row = row; c.xendsc = xendsc; c.xinc = xinc; c.length = length;
    c.scXh = scXh; c.scXl = scXl;
    c.r0 = r0; c.g0 = g0; c.b0 = b0; c.a0 = a0;
    c.z0 = z0; c.s0 = s0; c.t0 = t0; c.w0 = w0;
    c.drinc = drinc; c.dginc = dginc; c.dbinc = dbinc; c.dainc = dainc;
    c.dzinc = dzinc; c.dsinc = dsinc; c.dtinc = dtinc; c.dwinc = dwinc;
    c.flip = flip; c.ps = ps; c.pe = pe;
    c.spanDzPix = spanDzPix; c.spanDzEnc = spanDzEnc;
    c.ownerMode = ownerMode; c.needCvg = needCvg;
    c.shadeOn = shadeOn; c.textureOn = textureOn; c.persp = persp;
    c.texrect = texrect; c.copy = copy;
    c.trX0 = trX0; c.trY0 = trY0; c.trS = trS; c.trT = trT;
    c.trDsdx = trDsdx; c.trDtdy = trDtdy; c.trShift = trShift; c.trFlip = trFlip;
    return c;
}

__attribute__((always_inline))
static inline void span_pixel(span_ctx c, const u32 *__restrict rec, const u32 *__restrict u, i32 x, u32 *__restrict rdram, u8 *__restrict hidden, const u32 *__restrict zcom, const u32 *__restrict zdec, const u32 *__restrict deltaz, const u8 *__restrict tmem, const u32 *__restrict tcdiv)
{
    i32 row = c.row, xendsc = c.xendsc, xinc = c.xinc;
    i32 scXh = c.scXh, scXl = c.scXl;
    i32 r0 = c.r0, g0 = c.g0, b0 = c.b0, a0 = c.a0;
    i32 z0 = c.z0, s0 = c.s0, t0 = c.t0, w0 = c.w0;
    i32 drinc = c.drinc, dginc = c.dginc, dbinc = c.dbinc, dainc = c.dainc;
    i32 dzinc = c.dzinc, dsinc = c.dsinc, dtinc = c.dtinc, dwinc = c.dwinc;
    i32 flip = c.flip, ps = c.ps, pe = c.pe;
    i32 spanDzPix = c.spanDzPix, spanDzEnc = c.spanDzEnc;
    i32 ownerMode = c.ownerMode, needCvg = c.needCvg;
    i32 shadeOn = c.shadeOn, textureOn = c.textureOn, persp = c.persp;
    i32 texrect = c.texrect, copy = c.copy;
    i32 trX0 = c.trX0, trY0 = c.trY0, trS = c.trS, trT = c.trT;
    i32 trDsdx = c.trDsdx, trDtdy = c.trDtdy, trShift = c.trShift, trFlip = c.trFlip;

    {
        if (x >= scXh && x < scXl) {
            u32 step = (u32)((x - xendsc) * xinc);
            i32 r = (i32)((u32)r0 + (u32)drinc * step);
            i32 g = (i32)((u32)g0 + (u32)dginc * step);
            i32 b = (i32)((u32)b0 + (u32)dbinc * step);
            i32 a = (i32)((u32)a0 + (u32)dainc * step);
            i32 s = (i32)((u32)s0 + (u32)dsinc * step);
            i32 t = (i32)((u32)t0 + (u32)dtinc * step);
            i32 w = (i32)((u32)w0 + (u32)dwinc * step);
            i32 z = (i32)((u32)z0 + (u32)dzinc * step);
            if (texrect) {
                i32 dx = x - trX0, dy = row - trY0;
                i32 texS, texT;
                if (trFlip) {
                    texS = trS + ((dy * trDsdx) >> trShift);
                    texT = trT + ((dx * trDtdy) >> trShift);
                } else {
                    texS = trS + ((dx * trDsdx) >> trShift);
                    texT = trT + ((dy * trDtdy) >> trShift);
                }
                if (copy) {
                    u32 texel = sample_point(u, tmem, texS, texT);
                    if (!(i32)u[U_ALPHACOMPARE] || ((i32)(texel >> 24) & 0xFF) != 0)
                        write_color(rdram, u, x, row, texel);
                } else {
                    u32 texel = textureOn ? sample_tex(u, tmem, texS, texT) : 0u;
                    u32 color = combine(texel, texel, u[U_RECTSHADE], 0xFF, u);
                    write_pixel(rdram, hidden, zcom, zdec, deltaz, u, x, row, color, 0, 0, 8, spanDzPix, spanDzEnc);
                }
            } else {
            i32 draw = 1;
            i32 drawCvg = 8;
            if (needCvg) {
                i32 mask = coverage_at(x, flip, ps, pe, rec);
                i32 cvg = popcount8(mask);
                if (ownerMode) {
                    draw = cvg > 4 || (cvg == 4 && (mask & 0x80));
                    drawCvg = 8;
                } else {
                    draw = cvg != 0;
                    drawCvg = cvg;
                }
            }
            if (draw) {
                u32 shade = shadeOn ? pack(clamp255(r >> 16), clamp255(g >> 16), clamp255(b >> 16), clamp255(a >> 16)) : 0;
                i32 shadeAlpha = (i32)(shade >> 24) & 0xFF;
                u32 texel = 0;
                if (textureOn) {
                    i32 sc, tc;
                    divide(s, t, w, tcdiv, persp, &sc, &tc);
                    texel = sample_tex(u, tmem, sc, tc);
                }
                u32 color = combine(texel, texel, shade, shadeAlpha, u);
                i32 pixelZ = zcorrect((i32)u[U_ZSOURCE] ? ((i32)u[U_PRIMDEPTH] << 16) : z);
                write_pixel(rdram, hidden, zcom, zdec, deltaz, u, x, row, color, pixelZ, shadeAlpha, drawCvg, spanDzPix, spanDzEnc);
            }
            }
        }
    }
}

static void rdpspan_chain(const u32 *__restrict rec, const u32 *__restrict u, i32 lane, u32 *__restrict rdram, u8 *__restrict hidden, const u32 *__restrict zcom, const u32 *__restrict zdec, const u32 *__restrict deltaz, const u8 *__restrict tmem, const u32 *__restrict tcdiv)
{
    if ((i32)u[U_FILL]) {
        i32 xstart = (i32)rec[SPAN_LX];
        i32 xendsc = (i32)rec[SPAN_RX];
        for (i32 x = xstart + ((lane - xstart) & 63); x <= xendsc; x += 64)
            span_fill_at(rec, u, x, rdram, hidden);
        return;
    }

    const u8 *__restrict tex = tmem + rec[SPAN_TMEM] * TMEM_BYTES;
    span_ctx c = span_setup(rec, u);

    for (i32 pixel = (c.xinc * (lane - c.xendsc)) & 63; pixel <= c.length; pixel += 64)
        span_pixel(c, rec, u, c.xendsc + c.xinc * pixel, rdram, hidden, zcom, zdec, deltaz, tex, tcdiv);
}

static void rdpspan_one(const u32 *__restrict rec, const u32 *__restrict u, i32 x, u32 *__restrict rdram, u8 *__restrict hidden, const u32 *__restrict zcom, const u32 *__restrict zdec, const u32 *__restrict deltaz, const u8 *__restrict tmem, const u32 *__restrict tcdiv)
{
    if ((i32)u[U_FILL]) {
        span_fill_at(rec, u, x, rdram, hidden);
        return;
    }

    const u8 *__restrict tex = tmem + rec[SPAN_TMEM] * TMEM_BYTES;
    span_ctx c = span_setup(rec, u);
    i32 pixel = (x - c.xendsc) * c.xinc;
    if (pixel < 0 || pixel > c.length) return;
    span_pixel(c, rec, u, x, rdram, hidden, zcom, zdec, deltaz, tex, tcdiv);
}

#ifdef __AMDGCN__
__attribute__((amdgpu_kernel))
__attribute__((amdgpu_flat_work_group_size(64, 64)))
void rdpspan(u32 *__restrict rdram, u8 *__restrict hidden, const u32 *__restrict spans, const u32 *__restrict uniforms, const u32 *__restrict zcom, const u32 *__restrict zdec, const u32 *__restrict deltaz, const u8 *__restrict tmem, const u32 *__restrict tcdiv, const u32 *chainStart, const u32 *chainSpans, u32 chainCount)
{
    u32 t = __builtin_amdgcn_workgroup_id_x();
    if (t >= chainCount) return;

    i32 lane = (i32)__builtin_amdgcn_workitem_id_x();
    u32 end = chainStart[t + 1];

    for (u32 k = chainStart[t]; k < end; k++) {
        rdpspan_chain(spans + chainSpans[k] * SPAN_STRIDE, uniforms, lane,
                    rdram, hidden, zcom, zdec, deltaz, tmem, tcdiv);
    }
}
#endif
