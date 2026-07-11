.altmacro

.section .text

.macro ISR_STUB num
.global isr_stub_\num
.type isr_stub_\num, @function
isr_stub_\num:
    .if (\num == 8) || (\num == 10) || (\num == 11) || (\num == 12) || (\num == 13) || (\num == 14) || (\num == 17) || (\num == 21) || (\num == 29) || (\num == 30)
    .else
    pushq   $0
    .endif
    pushq   $\num
    jmp     isr_common
.endm

.set vec, 0
.rept 256
    ISR_STUB %vec
    .set vec, vec + 1
.endr

.extern isr_dispatch

.type isr_common, @function
isr_common:
    pushq   %r15
    pushq   %r14
    pushq   %r13
    pushq   %r12
    pushq   %r11
    pushq   %r10
    pushq   %r9
    pushq   %r8
    pushq   %rbp
    pushq   %rdi
    pushq   %rsi
    pushq   %rdx
    pushq   %rcx
    pushq   %rbx
    pushq   %rax

    cld
    movq    %rsp, %rdi
    call    isr_dispatch

    popq    %rax
    popq    %rbx
    popq    %rcx
    popq    %rdx
    popq    %rsi
    popq    %rdi
    popq    %rbp
    popq    %r8
    popq    %r9
    popq    %r10
    popq    %r11
    popq    %r12
    popq    %r13
    popq    %r14
    popq    %r15

    addq    $16, %rsp
    iretq
.size isr_common, . - isr_common

.global lgdt_load
.type lgdt_load, @function
lgdt_load:
    lgdt    (%rdi)
    movw    %dx, %ax
    movw    %ax, %ds
    movw    %ax, %es
    movw    %ax, %ss
    xorw    %ax, %ax
    movw    %ax, %gs
    movzwq  %si, %rax
    pushq   %rax
    leaq    .Lreload_cs(%rip), %rax
    pushq   %rax
    lretq
.Lreload_cs:
    ret
.size lgdt_load, . - lgdt_load

.global ltr_load
.type ltr_load, @function
ltr_load:
    ltr     %di
    ret
.size ltr_load, . - ltr_load

.global lidt_load
.type lidt_load, @function
lidt_load:
    lidt    (%rdi)
    ret
.size lidt_load, . - lidt_load

.section .rodata
.global isr_stub_table
.align 8

.macro ISR_ENTRY num
    .quad isr_stub_\num
.endm

isr_stub_table:
.set vec, 0
.rept 256
    ISR_ENTRY %vec
    .set vec, vec + 1
.endr

.section .note.GNU-stack,"",@progbits
