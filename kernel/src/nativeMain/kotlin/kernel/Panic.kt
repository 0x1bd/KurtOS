package kernel

import hal.Cpu
import hal.RawMemory
import hal.Serial
import kernel.console.SystemConsole
import kernel.graphics.GraphicsService
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName

private val EXCEPTION_NAMES = mapOf(
    0UL to "divide error",
    1UL to "debug",
    2UL to "non-maskable interrupt",
    3UL to "breakpoint",
    4UL to "overflow",
    5UL to "bound range exceeded",
    6UL to "invalid opcode",
    7UL to "device not available",
    8UL to "double fault",
    10UL to "invalid TSS",
    11UL to "segment not present",
    12UL to "stack fault",
    13UL to "general protection fault",
    14UL to "page fault",
    16UL to "x87 floating point",
    17UL to "alignment check",
    18UL to "machine check",
    19UL to "SIMD floating point",
    21UL to "control protection",
)

private val GENERAL_REGISTERS = listOf(
    "rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp",
    "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15",
)

private const val SLOT_VECTOR = 15
private const val SLOT_ERROR = 16
private const val SLOT_RIP = 17
private const val SLOT_CS = 18
private const val SLOT_RFLAGS = 19
private const val SLOT_RSP = 20
private const val SLOT_SS = 21

private const val COLOR_PANIC_BACKGROUND: UInt = 0x00600000u

@OptIn(ExperimentalNativeApi::class)
@CName("kernel_panic")
fun kernelPanic(frame: ULong) {
    val slot = { index: Int -> RawMemory.read64(frame + (index * 8).toULong()) }

    val vector = slot(SLOT_VECTOR)
    val name = EXCEPTION_NAMES[vector] ?: "unknown exception"

    paintPanicScreen()

    emit("")
    emit("*** KERNEL PANIC: $name ***")
    emit("vector ${vector} error ${hex(slot(SLOT_ERROR))}")
    emit("rip ${hex(slot(SLOT_RIP))}  cs ${hex(slot(SLOT_CS))}  rflags ${hex(slot(SLOT_RFLAGS))}")
    emit("rsp ${hex(slot(SLOT_RSP))}  ss ${hex(slot(SLOT_SS))}  cr2 ${hex(readCr2())}")

    for (i in GENERAL_REGISTERS.indices step 3) {
        val parts = mutableListOf<String>()
        for (j in i until minOf(i + 3, GENERAL_REGISTERS.size)) {
            parts.add("${GENERAL_REGISTERS[j].padEnd(3)} ${hex(slot(j))}")
        }
        emit(parts.joinToString("  "))
    }

    emit("")
    emit("system halted.")

    Cpu.hang()
}

private fun emit(line: String) = SystemConsole.println(line)

private fun paintPanicScreen() {
    val fb = GraphicsService.framebuffer() ?: return
    fb.clear(COLOR_PANIC_BACKGROUND)
    fb.presentAll()
    SystemConsole.reattachFramebuffer(COLOR_PANIC_BACKGROUND)
}

private fun readCr2(): ULong = Cpu.readCr2()

private fun hex(value: ULong): String {
    val digits = "0123456789abcdef"
    val builder = StringBuilder("0x")
    for (i in 15 downTo 0) {
        builder.append(digits[((value shr (i * 4)) and 0xFUL).toInt()])
    }
    return builder.toString()
}
