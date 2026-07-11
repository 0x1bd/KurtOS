import sys

def main():
    if len(sys.argv) != 3:
        print("Usage: generate_stubs.py <stubs.def> <stubs.c>")
        sys.exit(1)

    def_file = sys.argv[1]
    out_file = sys.argv[2]

    with open(def_file, 'r') as f:
        lines = f.readlines()

    with open(out_file, 'w') as f:
        f.write("#include <stddef.h>\n")
        f.write("#include <stdint.h>\n\n")
        f.write("extern void debug_print(const char* msg);\n")
        f.write("static int kurtos_errno = 0;\n")
        f.write("static void set_errno(int value) { kurtos_errno = value; }\n\n")

        for line in lines:
            line = line.strip()
            if not line or line.startswith("#"):
                continue

            parts = line.split(":", 1)
            if len(parts) != 2:
                continue

            sig, action = parts[0].strip(), parts[1].strip()

            f.write(f"{sig} {{\n")
            
            import re
            match = re.search(r'\((.*?)\)', sig)
            if match:
                args_str = match.group(1)
                if args_str and args_str != "void":
                    args = args_str.split(',')
                    for arg in args:
                        arg_name = re.sub(r'\[.*\]', '', arg.strip())
                        arg_name = re.sub(r'.*[\s\*]([a-zA-Z0-9_]+)$', r'\1', arg_name)
                        if arg_name != "..." and arg_name:
                            f.write(f"    (void){arg_name};\n")

            if action == "abort":
                func_name = re.search(r'([a-zA-Z0-9_]+)\(', sig).group(1)
                f.write(f"    debug_print(\"{func_name} abort\");\n")
                f.write(f"    for (;;) __asm__ volatile(\"cli; hlt\");\n")
            elif action == "return0":
                f.write("    return 0;\n")
            elif action == "return1":
                f.write("    return 1;\n")
            elif action == "return_pagesize":
                f.write("    return 4096;\n")
            elif action == "return_minus1":
                f.write("    return -1;\n")
            elif action == "return_minus1L":
                f.write("    return -1L;\n")
            elif action == "return_enosys":
                f.write("    set_errno(38);\n")
                f.write("    return -1;\n")
            elif action == "return_enosysL":
                f.write("    set_errno(38);\n")
                f.write("    return -1L;\n")
            elif action == "return_eagain":
                f.write("    return 11;\n")
            elif action == "return_null":
                f.write("    return (void*)0;\n")
            elif action == "return_errno_location":
                f.write("    return &kurtos_errno;\n")
            elif action == "return_unsupported_string":
                f.write("    return \"unsupported\";\n")
            elif action == "return_arg0":
                f.write(f"    return {args[0].strip().split()[-1].replace('*','')};\n")
            elif action == "return_malloc":
                f.write("    extern void *malloc(size_t);\n")
                f.write(f"    return malloc({args[0].strip().split()[-1].replace('*','')});\n")
            elif action == "free_arg0":
                f.write("    extern void free(void *);\n")
                f.write(f"    free({args[0].strip().split()[-1].replace('*','')});\n")
            elif action == "empty":
                pass

            f.write("}\n\n")

if __name__ == "__main__":
    main()
