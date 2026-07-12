#include <stddef.h>
#include <stdint.h>

void *memcpy(void *dst, const void *src, size_t n) {
    void *d = dst;
    const void *s = src;

    __asm__ volatile("rep movsb"
                     : "+D"(d), "+S"(s), "+c"(n)
                     :
                     : "memory");

    return dst;
}

void *memmove(void *dst, const void *src, size_t n) {
    uint8_t       *d = (uint8_t *)dst;
    const uint8_t *s = (const uint8_t *)src;

    if (d == s || n == 0)
        return dst;

    uintptr_t da = (uintptr_t)d;
    uintptr_t sa = (uintptr_t)s;

    if (da < sa || da >= sa + n)
        return memcpy(dst, src, n);

    for (size_t i = n; i > 0; i--)
        d[i - 1] = s[i - 1];

    return dst;
}

void *memset(void *s, int c, size_t n) {
    void *p = s;

    __asm__ volatile("rep stosb"
                     : "+D"(p), "+c"(n)
                     : "a"((uint8_t)c)
                     : "memory");

    return s;
}

int memcmp(const void *s1, const void *s2, size_t n) {
    const uint8_t *p1 = (const uint8_t *)s1;
    const uint8_t *p2 = (const uint8_t *)s2;

    for (size_t i = 0; i < n; i++) {
        if (p1[i] != p2[i])
            return p1[i] - p2[i];
    }

    return 0;
}

size_t strlen(const char *s) {
    size_t len = 0;

    while (s[len])
        len++;

    return len;
}

char *strcpy(char *dst, const char *src) {
    size_t i = 0;

    while ((dst[i] = src[i]) != '\0')
        i++;

    return dst;
}

char *strncpy(char *dst, const char *src, size_t n) {
    size_t i;

    for (i = 0; i < n && src[i] != '\0'; i++)
        dst[i] = src[i];

    for (; i < n; i++)
        dst[i] = '\0';

    return dst;
}

int strcmp(const char *s1, const char *s2) {
    while (*s1 && *s1 == *s2) {
        s1++;
        s2++;
    }

    return *(const unsigned char *)s1 - *(const unsigned char *)s2;
}

int strncmp(const char *s1, const char *s2, size_t n) {
    if (n == 0)
        return 0;

    while (n > 1 && *s1 && *s1 == *s2) {
        s1++;
        s2++;
        n--;
    }

    return *(const unsigned char *)s1 - *(const unsigned char *)s2;
}

char *strchr(const char *s, int c) {
    char ch = (char)c;

    while (*s != ch) {
        if (*s == '\0')
            return (char *)0;
        s++;
    }

    return (char *)s;
}

char *strrchr(const char *s, int c) {
    const char *last = (char *)0;
    char ch = (char)c;

    do {
        if (*s == ch)
            last = s;
    } while (*s++);

    return (char *)last;
}

char *strstr(const char *haystack, const char *needle) {
    size_t needle_len = strlen(needle);

    if (needle_len == 0)
        return (char *)haystack;

    for (; *haystack; haystack++) {
        if (*haystack == *needle &&
            memcmp(haystack, needle, needle_len) == 0) {
            return (char *)haystack;
        }
    }

    return (char *)0;
}

static int is_delim(char ch, const char *delim) {
    while (*delim) {
        if (ch == *delim)
            return 1;
        delim++;
    }

    return 0;
}

char *strtok_r(char *str, const char *delim, char **saveptr) {
    char *start;

    if (str == (char *)0)
        str = *saveptr;

    if (str == (char *)0)
        return (char *)0;

    while (*str && is_delim(*str, delim))
        str++;

    if (*str == '\0') {
        *saveptr = str;
        return (char *)0;
    }

    start = str;

    while (*str && !is_delim(*str, delim))
        str++;

    if (*str) {
        *str = '\0';
        *saveptr = str + 1;
    } else {
        *saveptr = str;
    }

    return start;
}

char *strtok(char *str, const char *delim) {
    static char *saveptr;

    return strtok_r(str, delim, &saveptr);
}

char *strsep(char **stringp, const char *delim) {
    char *start;
    char *p;

    if (stringp == (char **)0 || *stringp == (char *)0)
        return (char *)0;

    start = *stringp;
    p = start;

    while (*p) {
        if (is_delim(*p, delim)) {
            *p = '\0';
            *stringp = p + 1;
            return start;
        }
        p++;
    }

    *stringp = (char *)0;
    return start;
}

int bcmp(const void *s1, const void *s2, size_t n) {
    return memcmp(s1, s2, n);
}

void *memmem(const void *l, size_t l_len, const void *s, size_t s_len) {
    const uint8_t *haystack = (const uint8_t *)l;
    const uint8_t *needle   = (const uint8_t *)s;

    if (s_len == 0)
        return (void *)haystack;

    if (l_len < s_len)
        return (void *)0;

    for (size_t i = 0; i <= l_len - s_len; i++) {
        if (haystack[i] == needle[0] &&
            memcmp(haystack + i, needle, s_len) == 0) {
            return (void *)(haystack + i);
        }
    }

    return (void *)0;
}