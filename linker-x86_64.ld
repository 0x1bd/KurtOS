

OUTPUT_FORMAT(elf64-x86-64)
ENTRY(kmain_entry)

PHDRS
{
    text    PT_LOAD;
    rodata  PT_LOAD;
    data    PT_LOAD;
    tls     PT_TLS;
}

SECTIONS
{
    . = 0xffffffff80000000;

    __kernel_start = .;

    .text : {
        *(.text .text.*)
    } :text

    . = ALIGN(CONSTANT(MAXPAGESIZE));

    
    .limine_requests : {
        KEEP(*(.limine_requests_start))
        KEEP(*(.limine_requests))
        KEEP(*(.limine_requests_end))
    } :rodata

    .rodata : {
        *(.rodata .rodata.*)
    } :rodata

    
    .eh_frame : {
        __eh_frame_start = .;
        KEEP(*(.eh_frame))
        KEEP(*(.eh_frame.*))
        QUAD(0)
    } :rodata

    .gcc_except_table : {
        *(.gcc_except_table .gcc_except_table.*)
    } :rodata

    . = ALIGN(CONSTANT(MAXPAGESIZE));

    .data : {
        *(.data .data.*)
        *(.got .got.*)
        *(.got.plt)
    } :data

    .init_array : {
        . = ALIGN(8);
        __init_array_start = .;
        KEEP(*(SORT_BY_INIT_PRIORITY(.init_array.*)))
        KEEP(*(.init_array))
        __init_array_end = .;
    } :data

    
    . = ALIGN(64);

    .tdata : {
        __tdata_start = .;
        *(.tdata .tdata.*)
        __tdata_end = .;
    } :data :tls

    .tbss : {
        __tbss_start = .;
        *(.tbss .tbss.*)
        *(.tcommon)
        . = ALIGN(64);
        __tbss_end = .;
    } :tls

    .bss : {
        __bss_start = .;
        *(.bss .bss.*)
        *(COMMON)
        __bss_end = .;
    } :data

    __kernel_end = .;

    /DISCARD/ : {
        *(.note .note.*)
        *(.comment)
        *(.interp)
    }
}
