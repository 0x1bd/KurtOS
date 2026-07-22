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

That first `if` is in every kernel that dispatches more work items than it has work, and forgetting it
writes past the end of a buffer. Write it as a `bounds` clause instead and the compiler emits it:

```
kernel fn gradient(fb: global mut []u32, width: u32, height: u32, pitchpx: u32) bounds width * height {
    let gid = global_id_x()
    ...
}
```

`bounds e` means `if global_id_x() >= e { return }` at the top of the body. `e` is a `u32` and is
evaluated once. Only a kernel can have one.

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

A value is uniform when every work item in the workgroup sees the same one, and divergent when they do
not. Kernel parameters are uniform, because the whole workgroup is launched with one set of arguments.
`workgroup_id_x/y/z`, `workgroup_size_x/y/z` and `subgroup_size` are uniform. `global_id_x/y/z`,
`workitem_id_x/y/z` and `lane_id` are divergent, and so is anything computed from them, passed through a
function, or assigned inside a branch that is itself divergent.

The compiler tracks this and uses it for one rule: `barrier()` has to be reached by every lane. Put one
inside an `if` or a loop whose condition is divergent and the compile fails, because the lanes that skip
it wait forever for the lanes that do not:

```
if global_id_x() > 4 {
    barrier()               // error: not reached by every lane in the workgroup
}
```

An early `return` counts. Once some lanes have left, the rest of the function is divergent, so a
`bounds` clause and a `barrier()` do not go together in the same kernel. Leaving a loop early is
different: the lanes come back together when the loop ends, so a `barrier()` after the loop is fine and
one inside the rest of the loop body is not.

The rule is conservative in one way worth knowing. A local is divergent from the first place it is
assigned something divergent, for the whole function, not from that point on. Assigning a uniform value
back over it does not make it uniform again.

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