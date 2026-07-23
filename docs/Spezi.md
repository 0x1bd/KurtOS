# Spezi

Spezi is the shader language for KurtOS. Shaders live in `shaders/*.spz`. The build compiles each one
twice, once to AMD GCN for the GPU and once to x86-64 for the software renderer and for host tests.

The compiler is the `:spezi` Gradle module. It is written in Kotlin/Native and has no dependencies
beyond clang, which the build already needs. It emits LLVM IR as text and hands that to clang. 

## A first shader

```
kernel fn gradient(fb: global mut []u32, width: u32, height: u32, pitchpx: u32) {
    let gid = global_id_x()
    if gid >= width * height {
        return
    }

    let x = gid % width
    let y = gid / width
    let r = x * 255u32 / width
    let g = y * 255u32 / height

    fb[y * pitchpx + x] = (r << 16u32) | (g << 8u32) | 0x60u32
}
```

`kernel fn` marks an entry point. Everything else is a plain `fn` and gets inlined into the kernel.

That first `if` shows up in every kernel that gets dispatched wider than it has work. Forget it and you
write past the end of a buffer. Say it once in the signature instead and the compiler writes it for you:

```
kernel fn gradient(fb: global mut []u32, width: u32, height: u32, pitchpx: u32) bounds width * height {
    let gid = global_id_x()
    ...
}
```

`bounds e` is exactly `if global_id_x() >= e { return }` in front of the body. `e` has to be a `u32`.
Only a kernel can carry one, because only a kernel has work items to bound.

## Memory

There is no allocator. You cannot allocate memory, you can only name storage and pass pointers to it.
Storage lives in one of four places:

| Keyword | Where it lives |
| --- | --- |
| (none) | registers and per-lane scratch |
| `global` | buffers the caller passed in |
| `shared` | workgroup memory, GCN calls this LDS |
| `uniform` | read only parameters |

A buffer parameter is a slice, written `global mut []u32`. A slice is a pointer plus a length, so
`buf.len` works and the compiler can check indexes on the CPU build. Drop `mut` and the buffer becomes
read only, which the compiler enforces.

Local arrays have a fixed size and die at the end of the block that declared them. Structs and vectors
are values, so assigning one copies it. You can take the address of storage you declared with `&x`, but
you cannot return that pointer, because the storage it points at is already gone.

Workgroup memory is declared inside a kernel and has no initializer:

```
kernel fn blur(src: global []u32, dst: global mut []u32) {
    let shared mut tile: [64]u32
    tile[workitem_id_x()] = src[global_id_x()]
    barrier()
}
```

## Types

Integers are `i8 i16 i32 i64` and `u8 u16 u32 u64`. Floats are `f16 f32 f64`. There is also `bool`.

Vectors are written as the scalar type followed by `x` and a lane count, so `f32x4`, `u32x2`, `i16x8`.
Lane counts are 2, 3, 4, 8 and 16. Build one with `f32x4(1.0f, 2.0f, 3.0f, 4.0f)` or splat a single
value with `f32x4(0.5f)`. Read lanes with `.x .y .z .w` or `.r .g .b .a`, and reorder with `v.zyx`.

Arrays are `[16]u32`. Slices are `[]u32`. Pointers are `*u32`.

Mixing types is always explicit. `a + b` where one is `u32` and the other `i32` is an error, and you
write `a + u32(b)` instead.

Numbers are the exception, and they carry their type from context, so you almost never write a suffix.
A plain `5` becomes whatever it is used with:

```
let x: u32 = 5              // u32
let a = gid >> 1            // same type as gid
let b = gid & 0xFFFF        // same type as gid
for p in 0..primCount { }   // p takes the type of primCount
out[i] = cond ? 3 : 0       // both arms take the element type of out
```

Write a suffix when there is nothing to infer from, as in `let x = 5u8`. A number with no suffix and
no context at all is `i32`, or `f32` if it has a decimal point.

## Control flow

`if`, `else if` and `else` work as expected. There is a ternary, written `cond ? a : b`.

Loops are `while cond { }`, `for i in 0u32..n { }` and `loop { }`. Ranges are exclusive, and `0..=n`
includes the end. `break` and `continue` apply to the innermost loop. A `for` loop always steps by one,
so use a `while` loop when you need a different stride.

`switch`:

```
switch mode {
    case 0u32 -> { return 100u32 }
    case 1u32, 2u32 -> { return 200u32 }
    else -> { return 300u32 }
}
```

Case values have to be compile time constants and cannot repeat. `and` and `or` are the logical
operators and both short circuit. `&&` and `||` are not operators.

Every path through a function that returns a value has to return one.

## Operator precedence

Precedence follows Rust. Bitwise operators bind tighter than comparisons, so `v & 1 != 0` means
`(v & 1) != 0`.

There are no semicolons and no statement terminators. Statements end at the end of a line. This matters
in one place. A `*` at the start of a line is a pointer write, not a multiply:

```
let p = &out[0]
*p = 7u32
```

Splitting a long expression across lines works if you leave the operator at the end of the first line.

## Built in functions

Maths: `sqrt rsqrt abs sign min max clamp floor ceil round trunc fract sin cos tan exp exp2 log log2
pow atan2 fmod fma mix step smoothstep`. These work on scalars and on vectors, one lane at a time.

Bits: `popcount clz ctz bitreverse byteswap rotl rotr mulhi`, and `bits(value, offset, count)` to pull
a bitfield out.

Vectors: `dot length distance normalize cross`.

Reinterpreting bits: `f32_bits bits_f32 f64_bits bits_f64`. Also `select(cond, a, b)`.

Where am I: `global_id_x/y/z`, `workitem_id_x/y/z`, `workgroup_id_x/y/z`, `workgroup_size_x/y/z`,
`lane_id`, `subgroup_size`.

Synchronizing: `barrier()` waits for every lane in the workgroup. For ordering memory there is
`memfence_workgroup()`, which makes writes visible to the rest of the workgroup, and
`memfence_device()`, which makes them visible to the whole device. `memfence()` is the workgroup one
under a shorter name. Device scope is more expensive, so only reach for it when another workgroup has
to see the write.

## Uniform and divergent

A value is uniform when every work item in the workgroup sees the same one. It is divergent when they
see different ones. Kernel parameters are uniform, because the whole workgroup launches with one set of
arguments. So are `workgroup_id_x/y/z`, `workgroup_size_x/y/z` and `subgroup_size`.

`global_id_x/y/z`, `workitem_id_x/y/z` and `lane_id` are divergent. So is anything you compute from
them, anything a function returns after being handed one, and anything you assign inside a branch that
was itself divergent.

The compiler follows all of that through your code and spends it on one rule. `barrier()` has to be
reached by every lane. Put one under a condition that differs per lane and the build stops, because the
lanes that skip the barrier never arrive and the lanes that reached it wait forever:

```
if global_id_x() > 4 {
    barrier()               // error: not reached by every lane in the workgroup
}
```

An early `return` counts too. Once some lanes have left, everything after that point is divergent, which
is why a `bounds` clause and a `barrier()` cannot live in the same kernel. Leaving a loop early is not
the same thing. Lanes come back together when the loop ends, so a `barrier()` after the loop is fine and
one further down the same loop body is not.

One place the rule is blunter than it looks. A local counts as divergent for the whole function starting
at the first place something divergent lands in it. Assigning a uniform value back over it later does
not win it back.

## Calling a shader from the CPU

A kernel compiled for x86-64 becomes an ordinary C function. Slice parameters turn into two arguments,
a pointer and a length. The work item indexes cannot come from hardware, so they come from a small
struct passed as the first argument:

```c
typedef struct { unsigned item[3]; unsigned group[3]; unsigned size[3]; } spz_dispatch;
void gradient(const spz_dispatch *d, unsigned *fb, int fb_len,
              unsigned width, unsigned height, unsigned pitchpx);
```

Fill in the struct and call the function once per work item. `global_id_x()` inside the shader reads
`group[0] * size[0] + item[0]`, the same arithmetic the GPU does.

The struct is nine consecutive 32 bit values, so from Kotlin an `IntArray(9)` pinned in memory works
as the argument. `SoftwareGpu` in the n64 module does exactly that.

On the GPU side nothing extra is passed, because the hardware supplies those numbers.

A slice costs you an argument on the GPU too. The kernel argument block gets a pointer and a length
where a raw pointer would have put only a pointer, so every argument behind it moves. Host code that
packs the block by hand has to agree, and when it does not the shader reads whatever the neighbouring
field happened to hold. That is a memory corruption bug that looks like a shader bug, so the build
checks it. `CompileSpeziShaders` knows the size each kernel argument block should come out as and fails
the build when the compiled size disagrees. If you add or remove a parameter, or turn a pointer into a
slice, update `expectedKernarg` in the same commit and let the check confirm the new number.

## Building shaders by hand

The build does this for you, but when a shader misbehaves it helps to run the compiler directly:

```
spezi --target amdgcn --cpu gfx902 -O2 -o out.hsaco shader.spz
spezi --target x86_64 -O2 -o out.o shader.spz
spezi --target amdgcn --emit-ir -o out.ll shader.spz
```

`--emit-ir` stops after writing LLVM IR, which is the fastest way to see what the compiler actually
did. Targets are `x86_64`, `amdgcn`, `nvptx64` and `spirv64`.

## Viewing a buffer at a different width

Sometimes a buffer arrives as one type and has to be read as another. The N64 framebuffer is the usual
case, where RDRAM is handed over as `u32` but pixels are 16 bits. Cast the slice to a slice of the
other type and you get a view of the same memory:

```
let words = rdram as global mut []u16
words[(masked >> 1) ^ 1] = u16(value)
```

A view is still a slice, so it still has a length and is still bounds checked. The compiler rescales
the length by the ratio of the two element sizes, so a 1000 element `[]u32` becomes a 2000 element
`[]u16`. The address space carries over, so a `global` buffer stays `global`.

A view cannot gain mutability, so a read only buffer cannot be viewed as a
mutable one. The two element sizes have to divide evenly, so `u32` to `u16` is fine and `u32` to
a three byte type is not.

You can still cast a slice down to a raw pointer with `as global mut *u16`. That works and is
sometimes what you want, but it throws the length away and nothing is bounds checked through it, so
prefer the view.