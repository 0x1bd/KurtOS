#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>

#define COM1 0x3F8

void *stdin  = (void*)0;
void *stdout = (void*)1;
void *stderr = (void*)2;

extern void debug_print(const char *msg);

static inline void outb(uint16_t port, uint8_t value) {
    __asm__ volatile("outb %0, %1" : : "a"(value), "Nd"(port));
}

static inline uint8_t inb(uint16_t port) {
    uint8_t value;
    __asm__ volatile("inb %1, %0" : "=a"(value) : "Nd"(port));
    return value;
}

static void uart_putchar(int c) {
    for (int i = 0; i < 100000 && !(inb(COM1 + 5) & 0x20); i++) { }
    outb(COM1, (unsigned char)c);
}

static void uart_puts_raw(const char *s) {
    while (*s) uart_putchar((unsigned char)*s++);
}

long write(int fd, const void *buf, size_t count) {
    (void)fd;
    const unsigned char *p = (const unsigned char *)buf;
    for (size_t i = 0; i < count; i++) uart_putchar(p[i]);
    return count;
}

long read(int fd, void *buf, size_t count) {
    (void)fd;
    (void)buf;
    (void)count;
    return 0;
}

int puts(const char *s) {
    uart_puts_raw(s);
    uart_putchar('\n');
    return 0;
}

int putchar(int c) {
    uart_putchar(c);
    return c;
}

int getchar(void) {
    return -1;
}

int fgetc(void *stream) {
    (void)stream;
    return -1;
}

int ferror(void *stream) {
    (void)stream;
    return 0;
}

int fputs(const char *s, void *stream) {
    (void)stream;
    uart_puts_raw(s);
    return 0;
}

int fputc(int c, void *stream) {
    (void)stream;
    uart_putchar(c);
    return c;
}

unsigned long fwrite(const void *ptr, unsigned long size, unsigned long n, void *stream) {
    (void)stream;
    write(1, ptr, size * n);
    return n;
}

unsigned long strnlen(const char *s, unsigned long maxlen) {
    unsigned long i = 0;
    while (i < maxlen && s[i]) i++;
    return i;
}

static void reverse_str(char *str, int len) {
    int i = 0, j = len - 1;
    while (i < j) {
        char tmp = str[i];
        str[i] = str[j];
        str[j] = tmp;
        i++; j--;
    }
}

static int itoa(int64_t num, char *str, int base, int is_signed) {
    int i = 0;
    int is_neg = 0;

    if (num == 0) {
        str[i++] = '0';
        str[i] = '\0';
        return 1;
    }

    if (is_signed && num < 0 && base == 10) {
        is_neg = 1;
        num = -num;
    }

    uint64_t unum = (uint64_t)num;
    while (unum != 0) {
        int rem = unum % base;
        str[i++] = (rem > 9) ? (rem - 10) + 'a' : rem + '0';
        unum = unum / base;
    }

    if (is_neg) str[i++] = '-';
    str[i] = '\0';
    reverse_str(str, i);
    return i;
}

static int fmt_putc(char **out, size_t *avail, char c) {
    if (*avail > 1) {
        **out = c;
        (*out)++;
        (*avail)--;
    }
    return 1;
}

static int fmt_puts(char **out, size_t *avail, const char *s) {
    int count = 0;
    while (*s) {
        fmt_putc(out, avail, *s++);
        count++;
    }
    return count;
}

int vsnprintf(char *str, size_t size, const char *format, va_list ap) {
    if (size == 0) return 0;

    char *out = str;
    size_t avail = size;
    int count = 0;
    char buf[64];

    while (*format) {
        if (*format != '%') {
            count += fmt_putc(&out, &avail, *format++);
            continue;
        }

        format++;
        int is_long = 0;
        if (*format == 'l') {
            is_long = 1;
            format++;
            if (*format == 'l') { is_long = 2; format++; }
        }

        switch (*format) {
            case 'c': {
                char c = (char)va_arg(ap, int);
                count += fmt_putc(&out, &avail, c);
                break;
            }
            case 's': {
                const char *s = va_arg(ap, const char *);
                if (!s) s = "(null)";
                count += fmt_puts(&out, &avail, s);
                break;
            }
            case 'd':
            case 'i': {
                int64_t val = (is_long == 2) ? va_arg(ap, long long) :
                              (is_long == 1) ? va_arg(ap, long) :
                                               va_arg(ap, int);
                itoa(val, buf, 10, 1);
                count += fmt_puts(&out, &avail, buf);
                break;
            }
            case 'u': {
                uint64_t val = (is_long == 2) ? va_arg(ap, unsigned long long) :
                               (is_long == 1) ? va_arg(ap, unsigned long) :
                                                va_arg(ap, unsigned int);
                itoa((int64_t)val, buf, 10, 0);
                count += fmt_puts(&out, &avail, buf);
                break;
            }
            case 'x':
            case 'X':
            case 'p': {
                if (*format == 'p') {
                    fmt_puts(&out, &avail, "0x");
                    count += 2;
                }
                uint64_t val = (*format == 'p') ? (uint64_t)va_arg(ap, void *) :
                               (is_long == 2)   ? va_arg(ap, unsigned long long) :
                               (is_long == 1)   ? va_arg(ap, unsigned long) :
                                                  va_arg(ap, unsigned int);
                itoa((int64_t)val, buf, 16, 0);
                count += fmt_puts(&out, &avail, buf);
                break;
            }
            case '%': {
                count += fmt_putc(&out, &avail, '%');
                break;
            }
            default:
                count += fmt_putc(&out, &avail, '%');
                count += fmt_putc(&out, &avail, *format);
                break;
        }
        format++;
    }

    if (avail > 0) *out = '\0';
    return count;
}

int snprintf(char *str, size_t size, const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(str, size, format, ap);
    va_end(ap);
    return ret;
}

int sprintf(char *str, const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(str, 0x7FFFFFFF, format, ap);
    va_end(ap);
    return ret;
}

int fprintf(void *stream, const char *format, ...) {
    (void)stream;
    char buf[512];
    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(buf, sizeof(buf), format, ap);
    va_end(ap);
    uart_puts_raw(buf);
    return ret;
}

int printf(const char *format, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(buf, sizeof(buf), format, ap);
    va_end(ap);
    uart_puts_raw(buf);
    return ret;
}

int isdigit(int c)  { return (c >= '0' && c <= '9'); }
int isspace(int c)  { return (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\v' || c == '\f'); }
int isalpha(int c)  { return ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')); }
int isalnum(int c)  { return isalpha(c) || isdigit(c); }
int isxdigit(int c) { return isdigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }
int toupper(int c)  { return (c >= 'a' && c <= 'z') ? c - 32 : c; }
int tolower(int c)  { return (c >= 'A' && c <= 'Z') ? c + 32 : c; }

long strtol(const char *nptr, char **endptr, int base) {
    const char *s = nptr;
    long acc = 0;
    int c;
    int neg = 0;

    while (isspace((unsigned char)*s)) s++;
    if (*s == '-') { neg = 1; s++; }
    else if (*s == '+') { s++; }

    if ((base == 0 || base == 16) && *s == '0' && (*(s+1) == 'x' || *(s+1) == 'X')) {
        s += 2;
        base = 16;
    }
    if (base == 0) base = (*s == '0') ? 8 : 10;

    while (*s) {
        c = (unsigned char)*s;
        if (isdigit(c)) c -= '0';
        else if (isalpha(c)) c = toupper(c) - 'A' + 10;
        else break;
        if (c >= base) break;
        acc = acc * base + c;
        s++;
    }

    if (endptr) *endptr = (char *)s;
    return neg ? -acc : acc;
}

unsigned long strtoul(const char *nptr, char **endptr, int base) {
    return (unsigned long)strtol(nptr, endptr, base);
}

double strtod(const char *nptr, char **endptr) {
    long whole = strtol(nptr, endptr, 10);
    return (double)whole;
}

typedef unsigned int pthread_key_t;
typedef int pthread_once_t;
#define MAX_KEYS 32
static void *tls_values[MAX_KEYS] = {0};
static int next_key = 0;

int pthread_key_create(pthread_key_t *key, void (*destructor)(void *)) {
    (void)destructor;
    if (next_key >= MAX_KEYS) return 11;
    *key = next_key++;
    return 0;
}

void *pthread_getspecific(pthread_key_t key) {
    if (key >= MAX_KEYS) return (void *)0;
    return tls_values[key];
}

int pthread_setspecific(pthread_key_t key, const void *value) {
    if (key >= MAX_KEYS) return 22;
    tls_values[key] = (void *)value;
    return 0;
}

int pthread_key_delete(pthread_key_t key) {
    if (key >= MAX_KEYS) return 22;
    tls_values[key] = (void *)0;
    return 0;
}

int pthread_once(pthread_once_t *once, void (*init_routine)(void)) {
    uint8_t *flag = (uint8_t *)once;
    if (!*flag) { *flag = 1; init_routine(); }
    return 0;
}
