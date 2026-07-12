.section .text

.globl kthread_switch
.type kthread_switch, @function
kthread_switch:
    pushq %rbp
    pushq %rbx
    pushq %r12
    pushq %r13
    pushq %r14
    pushq %r15

    movq %rsp, (%rdi)
    movq %rsi, %rsp

    movq %rdx, %rax
    shrq $32, %rdx
    movl $0xC0000100, %ecx
    wrmsr

    popq %r15
    popq %r14
    popq %r13
    popq %r12
    popq %rbx
    popq %rbp
    ret
.size kthread_switch, . - kthread_switch

.section .note.GNU-stack,"",@progbits
