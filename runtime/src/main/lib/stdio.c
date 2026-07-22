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

extern int isnan(double x);
extern int isinf(double x);
extern double fabs(double x);
extern double floor(double x);

static const uint64_t POW10[] = {
    1ULL, 10ULL, 100ULL, 1000ULL, 10000ULL, 100000ULL, 1000000ULL,
    10000000ULL, 100000000ULL, 1000000000ULL, 10000000000ULL,
    100000000000ULL, 1000000000000ULL, 10000000000000ULL,
    100000000000000ULL, 1000000000000000ULL, 10000000000000000ULL,
    100000000000000000ULL, 1000000000000000000ULL,
};
#define POW10_MAX 18
#define FLOAT_PREC_MAX 200

typedef struct {
    char  *out;
    size_t cap;
    size_t len;
    int    count;
} sink_t;

static void sink_putc(sink_t *s, char c) {
    if (s->cap != 0 && s->len + 1 < s->cap) s->out[s->len++] = c;
    s->count++;
}

static void sink_pad(sink_t *s, char c, int n) {
    for (int i = 0; i < n; i++) sink_putc(s, c);
}

static void sink_write(sink_t *s, const char *p, int n) {
    for (int i = 0; i < n; i++) sink_putc(s, p[i]);
}

static int u64_to_str(char *buf, uint64_t v, int base, int upper) {
    const char *digits = upper ? "0123456789ABCDEF" : "0123456789abcdef";
    char tmp[24];
    int n = 0;
    if (v == 0) tmp[n++] = '0';
    while (v != 0) { tmp[n++] = digits[v % (unsigned)base]; v /= (unsigned)base; }
    for (int i = 0; i < n; i++) buf[i] = tmp[n - 1 - i];
    return n;
}

static void emit_field(sink_t *s, const char *sign, const char *prefix,
                       int zeros, const char *body, int blen,
                       int width, int left, int zero) {
    int slen = 0; while (sign[slen]) slen++;
    int plen = 0; while (prefix[plen]) plen++;
    int total = slen + plen + zeros + blen;
    int pad = width > total ? width - total : 0;

    if (!left && !zero) sink_pad(s, ' ', pad);
    sink_write(s, sign, slen);
    sink_write(s, prefix, plen);
    if (!left && zero) sink_pad(s, '0', pad);
    sink_pad(s, '0', zeros);
    sink_write(s, body, blen);
    if (left) sink_pad(s, ' ', pad);
}

static int dbl_int_str(char *buf, double v) {
    char tmp[340];
    int n = 0;
    if (v < 1.0) { buf[0] = '0'; return 1; }
    while (v >= 1.0 && n < (int)sizeof(tmp)) {
        double q = floor(v / 10.0);
        int d = (int)(v - q * 10.0);
        if (d < 0) d = 0;
        if (d > 9) d = 9;
        tmp[n++] = (char)('0' + d);
        v = q;
    }
    for (int i = 0; i < n; i++) buf[i] = tmp[n - 1 - i];
    return n;
}

static int fmt_fixed(char *buf, double v, int prec, int alt) {
    int n = 0;
    double intpart = floor(v);
    double frac = v - intpart;
    int cap = prec > POW10_MAX ? POW10_MAX : prec;

    uint64_t scaled = 0;
    if (cap > 0) {
        scaled = (uint64_t)(frac * (double)POW10[cap] + 0.5);
        if (scaled >= POW10[cap]) { scaled -= POW10[cap]; intpart += 1.0; }
    } else if (frac + 0.5 >= 1.0) {
        intpart += 1.0;
    }

    n += dbl_int_str(buf + n, intpart);
    if (prec > 0 || alt) buf[n++] = '.';
    if (prec > 0) {
        char fd[POW10_MAX];
        uint64_t x = scaled;
        for (int i = 0; i < cap; i++) { fd[cap - 1 - i] = (char)('0' + x % 10); x /= 10; }
        for (int i = 0; i < cap; i++) buf[n++] = fd[i];
        for (int i = cap; i < prec; i++) buf[n++] = '0';
    }
    return n;
}

static int fmt_exp(char *buf, double v, int prec, char e, int alt) {
    int n = 0;
    int exp = 0;
    if (v != 0.0) {
        while (v >= 10.0) { v /= 10.0; exp++; }
        while (v < 1.0)   { v *= 10.0; exp--; }
    }

    int cap = prec > POW10_MAX ? POW10_MAX : prec;
    uint64_t m = (uint64_t)(v * (double)POW10[cap] + 0.5);
    if (m >= 10ULL * POW10[cap]) { m /= 10; exp++; }

    char md[POW10_MAX + 2] = {0};
    int mn = cap + 1;
    uint64_t x = m;
    for (int i = 0; i < mn; i++) { md[mn - 1 - i] = (char)('0' + x % 10); x /= 10; }

    buf[n++] = md[0];
    if (prec > 0 || alt) buf[n++] = '.';
    for (int i = 1; i <= cap; i++) buf[n++] = md[i];
    for (int i = cap; i < prec; i++) buf[n++] = '0';

    buf[n++] = e;
    if (exp < 0) { buf[n++] = '-'; exp = -exp; } else buf[n++] = '+';
    char ed[8];
    int en = 0;
    if (exp == 0) ed[en++] = '0';
    while (exp != 0) { ed[en++] = (char)('0' + exp % 10); exp /= 10; }
    while (en < 2) ed[en++] = '0';
    for (int i = 0; i < en; i++) buf[n++] = ed[en - 1 - i];
    return n;
}

static int strip_trailing_zeros(char *buf, int n) {
    int dot = -1;
    for (int i = 0; i < n; i++) if (buf[i] == '.') { dot = i; break; }
    if (dot < 0) return n;

    int e = -1;
    for (int i = dot; i < n; i++) if (buf[i] == 'e' || buf[i] == 'E') { e = i; break; }
    int endfrac = (e < 0) ? n : e;

    int last = endfrac;
    while (last > dot + 1 && buf[last - 1] == '0') last--;
    if (last == dot + 1) last--;

    if (e < 0) return last;
    int shift = endfrac - last;
    for (int i = e; i < n; i++) buf[i - shift] = buf[i];
    return n - shift;
}

static int fmt_general(char *buf, double v, int prec, int upper, int alt) {
    if (prec == 0) prec = 1;

    int exp = 0;
    double t = v;
    if (t != 0.0) {
        while (t >= 10.0) { t /= 10.0; exp++; }
        while (t < 1.0)   { t *= 10.0; exp--; }
    }

    int n;
    if (exp < -4 || exp >= prec)
        n = fmt_exp(buf, v, prec - 1, upper ? 'E' : 'e', alt);
    else
        n = fmt_fixed(buf, v, prec - 1 - exp, alt);

    if (!alt) n = strip_trailing_zeros(buf, n);
    return n;
}

static int dbl_negative(double v) {
    union { double d; uint64_t u; } b;
    b.d = v;
    return (int)(b.u >> 63);
}

static void fmt_float(sink_t *s, double v, int prec, char conv,
                      int width, int left, int zero,
                      int plus, int space, int alt) {
    int upper = (conv == 'F' || conv == 'E' || conv == 'G');
    char lc = (char)(conv | 0x20);

    int neg = dbl_negative(v);
    v = fabs(v);
    const char *sign = neg ? "-" : (plus ? "+" : (space ? " " : ""));

    if (isnan(v)) { emit_field(s, sign, "", 0, upper ? "NAN" : "nan", 3, width, left, 0); return; }
    if (isinf(v)) { emit_field(s, sign, "", 0, upper ? "INF" : "inf", 3, width, left, 0); return; }

    if (prec < 0) prec = 6;
    if (prec > FLOAT_PREC_MAX) prec = FLOAT_PREC_MAX;

    char body[FLOAT_PREC_MAX + 360];
    int n;
    if (lc == 'f') n = fmt_fixed(body, v, prec, alt);
    else if (lc == 'e') n = fmt_exp(body, v, prec, upper ? 'E' : 'e', alt);
    else n = fmt_general(body, v, prec, upper, alt);

    emit_field(s, sign, "", 0, body, n, width, left, zero);
}

int vsnprintf(char *str, size_t size, const char *format, va_list ap) {
    sink_t s = { str, size, 0, 0 };

    while (*format) {
        if (*format != '%') { sink_putc(&s, *format++); continue; }
        format++;

        int left = 0, zero = 0, plus = 0, space = 0, alt = 0;
        for (;;) {
            char c = *format;
            if (c == '-') left = 1;
            else if (c == '0') zero = 1;
            else if (c == '+') plus = 1;
            else if (c == ' ') space = 1;
            else if (c == '#') alt = 1;
            else break;
            format++;
        }

        int width = 0;
        if (*format == '*') {
            width = va_arg(ap, int);
            format++;
            if (width < 0) { left = 1; width = -width; }
        } else {
            while (*format >= '0' && *format <= '9') width = width * 10 + (*format++ - '0');
        }

        int prec = -1;
        if (*format == '.') {
            format++;
            prec = 0;
            if (*format == '*') {
                prec = va_arg(ap, int);
                format++;
                if (prec < 0) prec = -1;
            } else {
                while (*format >= '0' && *format <= '9') prec = prec * 10 + (*format++ - '0');
            }
        }

        int lenmod = 0;
        if (*format == 'h') { format++; if (*format == 'h') { lenmod = -2; format++; } else lenmod = -1; }
        else if (*format == 'l') { format++; if (*format == 'l') { lenmod = 2; format++; } else lenmod = 1; }
        else if (*format == 'z' || *format == 't' || *format == 'j') { lenmod = 3; format++; }
        else if (*format == 'L') { format++; }

        char conv = *format;
        if (!conv) break;
        format++;

        if (conv == '%') { sink_putc(&s, '%'); continue; }

        if (conv == 'c') {
            char body = (char)va_arg(ap, int);
            emit_field(&s, "", "", 0, &body, 1, width, left, 0);
            continue;
        }

        if (conv == 's') {
            const char *arg = va_arg(ap, const char *);
            if (!arg) arg = "(null)";
            int blen = 0;
            while (arg[blen] && (prec < 0 || blen < prec)) blen++;
            emit_field(&s, "", "", 0, arg, blen, width, left, 0);
            continue;
        }

        if (conv == 'd' || conv == 'i' || conv == 'u' ||
            conv == 'x' || conv == 'X' || conv == 'o' || conv == 'p') {
            int base = 10, upper = 0, is_signed = (conv == 'd' || conv == 'i');
            const char *prefix = "";
            const char *sign = "";
            uint64_t mag;

            if (conv == 'x') base = 16;
            else if (conv == 'X') { base = 16; upper = 1; }
            else if (conv == 'o') base = 8;
            else if (conv == 'p') { base = 16; }

            if (conv == 'p') {
                mag = (uint64_t)(uintptr_t)va_arg(ap, void *);
            } else if (is_signed) {
                long long v;
                if (lenmod == 2) v = va_arg(ap, long long);
                else if (lenmod == 1 || lenmod == 3) v = va_arg(ap, long);
                else v = va_arg(ap, int);
                if (lenmod == -1) v = (short)v;
                else if (lenmod == -2) v = (signed char)v;
                if (v < 0) { sign = "-"; mag = (uint64_t)(-(v + 1)) + 1ULL; }
                else { mag = (uint64_t)v; sign = plus ? "+" : (space ? " " : ""); }
            } else {
                unsigned long long v;
                if (lenmod == 2) v = va_arg(ap, unsigned long long);
                else if (lenmod == 1) v = va_arg(ap, unsigned long);
                else if (lenmod == 3) v = va_arg(ap, size_t);
                else v = va_arg(ap, unsigned int);
                if (lenmod == -1) v = (unsigned short)v;
                else if (lenmod == -2) v = (unsigned char)v;
                mag = (uint64_t)v;
            }

            char digits[26];
            int dn = u64_to_str(digits, mag, base, upper);

            if (alt && base == 16 && mag != 0) prefix = upper ? "0X" : "0x";
            if (conv == 'p') prefix = "0x";

            if (alt && base == 8 && digits[0] != '0') {
                for (int i = dn; i > 0; i--) digits[i] = digits[i - 1];
                digits[0] = '0';
                dn++;
            }

            int zeros = 0;
            if (prec >= 0) {
                if (dn < prec) zeros = prec - dn;
                if (prec == 0 && mag == 0) dn = 0;
            }
            int usezero = (zero && prec < 0 && !left) ? 1 : 0;

            emit_field(&s, sign, prefix, zeros, digits, dn, width, left, usezero);
            continue;
        }

        if (conv == 'f' || conv == 'F' || conv == 'e' || conv == 'E' ||
            conv == 'g' || conv == 'G') {
            double v = va_arg(ap, double);
            fmt_float(&s, v, prec, conv, width, left, zero, plus, space, alt);
            continue;
        }

        sink_putc(&s, '%');
        sink_putc(&s, conv);
    }

    if (s.cap != 0) s.out[s.len] = '\0';
    return s.count;
}

int snprintf(char *str, size_t size, const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(str, size, format, ap);
    va_end(ap);
    return ret;
}

int vsprintf(char *str, const char *format, va_list ap) {
    return vsnprintf(str, 0x7FFFFFFF, format, ap);
}

int sprintf(char *str, const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    int ret = vsnprintf(str, 0x7FFFFFFF, format, ap);
    va_end(ap);
    return ret;
}

int __vsnprintf_chk(char *str, size_t size, int flag, size_t slen, const char *format, va_list ap) {
    (void)flag;
    return vsnprintf(str, size < slen ? size : slen, format, ap);
}

int __snprintf_chk(char *str, size_t size, int flag, size_t slen, const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    int ret = __vsnprintf_chk(str, size, flag, slen, format, ap);
    va_end(ap);
    return ret;
}

int __vsprintf_chk(char *str, int flag, size_t slen, const char *format, va_list ap) {
    (void)flag;
    return vsnprintf(str, slen == (size_t)-1 ? 0x7FFFFFFF : slen, format, ap);
}

int __sprintf_chk(char *str, int flag, size_t slen, const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    int ret = __vsprintf_chk(str, flag, slen, format, ap);
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
    const char *s = nptr;

    while (isspace((unsigned char)*s)) s++;

    int neg = 0;
    if (*s == '+') s++;
    else if (*s == '-') { neg = 1; s++; }

    double result = 0.0;
    int any = 0;

    while (isdigit((unsigned char)*s)) {
        result = result * 10.0 + (*s - '0');
        s++;
        any = 1;
    }

    if (*s == '.') {
        s++;
        double scale = 0.1;
        while (isdigit((unsigned char)*s)) {
            result += (*s - '0') * scale;
            scale *= 0.1;
            s++;
            any = 1;
        }
    }

    if (any && (*s == 'e' || *s == 'E')) {
        const char *es = s + 1;
        int esign = 0;
        if (*es == '+') es++;
        else if (*es == '-') { esign = 1; es++; }

        if (isdigit((unsigned char)*es)) {
            int exp = 0;
            while (isdigit((unsigned char)*es)) { exp = exp * 10 + (*es - '0'); es++; }
            double p = 1.0;
            for (int i = 0; i < exp; i++) p *= 10.0;
            if (esign) result /= p; else result *= p;
            s = es;
        }
    }

    if (endptr) *endptr = (char *)(any ? s : nptr);
    return neg ? -result : result;
}

typedef unsigned int pthread_key_t;
typedef int pthread_once_t;
#define MAX_KEYS 32
static _Thread_local void *tls_values[MAX_KEYS];
static int next_key = 0;

int pthread_key_create(pthread_key_t *key, void (*destructor)(void *)) {
    (void)destructor;
    int slot = __atomic_fetch_add(&next_key, 1, __ATOMIC_ACQ_REL);
    if (slot >= MAX_KEYS) return 11;
    *key = (pthread_key_t)slot;
    return 0;
}

int __pthread_key_create(pthread_key_t *key, void (*destructor)(void *)) {
    return pthread_key_create(key, destructor);
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
    int expected = 0;
    if (__atomic_compare_exchange_n((int *)once, &expected, 1, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        init_routine();
        __atomic_store_n((int *)once, 2, __ATOMIC_RELEASE);
        return 0;
    }
    while (__atomic_load_n((int *)once, __ATOMIC_ACQUIRE) != 2) {
        __asm__ volatile("pause");
    }
    return 0;
}
