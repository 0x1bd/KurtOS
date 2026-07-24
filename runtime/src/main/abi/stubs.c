#include <stddef.h>
#include <stdint.h>

extern void debug_print(const char *msg);
extern void *malloc(size_t size);
extern void free(void *ptr);

static int kurtos_errno = 0;

#define STUB_ABORT(name)  do { debug_print(#name " abort"); for (;;) __asm__ volatile("cli; hlt"); } while (0)
#define STUB_ENOSYS       do { kurtos_errno = 38; return -1; } while (0)
#define STUB_ENOSYS_L     do { kurtos_errno = 38; return -1L; } while (0)

void abort(void) { STUB_ABORT(abort); }
void exit(int code) { STUB_ABORT(exit); }
void _Exit(int status) { STUB_ABORT(_Exit); }
int raise(int sig) { STUB_ABORT(raise); }
int sigaction(int signum, const void *act, void *oldact) { STUB_ENOSYS; }
int sigemptyset(void *set) { return 0; }
int __fxstat(int ver, int fd, void *stat_buf) { STUB_ENOSYS; }
long syscall(long number, ...) { STUB_ENOSYS_L; }

unsigned int sleep(unsigned int seconds) { STUB_ABORT(sleep); }
int getpagesize(void) { return 4096; }
int gettimeofday(void *tv, void *tz) { STUB_ENOSYS; }
int getrusage(int who, void *usage) { STUB_ENOSYS; }
int getpid(void) { return 1; }
char *getenv(const char *name) { return (void *)0; }
int fcntl(int fd, int cmd, ...) { STUB_ENOSYS; }
void *fdopen(int fd, const char *mode) { return (void *)0; }
long readlink(const char *path, char *buf, unsigned long len) { STUB_ENOSYS_L; }
int pthread_setname_np(unsigned long thread, const char *name) { return 0; }
int __lxstat(int ver, const char *path, void *buf) { STUB_ENOSYS; }

int fflush(void *stream) { return 0; }
int close(int fd) { STUB_ENOSYS; }
int open(const char *path, int flags, ...) { STUB_ENOSYS; }
long lseek(int fd, long offset, int whence) { STUB_ENOSYS_L; }
int dl_iterate_phdr(int (*callback)(void *, size_t, void *), void *data) { return 0; }
int dladdr(const void *addr, void *info) { return 0; }
int *__errno_location(void) { return &kurtos_errno; }
char *strerror(int errnum) { return "unsupported"; }

int __cxa_atexit(void (*f)(void *), void *p, void *d) { return 0; }

void __assert_fail(const char *assertion, const char *file, unsigned int line, const char *function) { STUB_ABORT(__assert_fail); }
void __stack_chk_fail(void) { STUB_ABORT(__stack_chk_fail); }
long __getauxval(unsigned long type) { return 0; }
void _ZSt21__glibcxx_assert_failPKciS0_S0_(const char *file, int line, const char *fn, const char *cond) { STUB_ABORT(_ZSt21__glibcxx_assert_failPKciS0_S0_); }

unsigned int _ZNSt6thread20hardware_concurrencyEv(void) { return 1; }
void _ZNSt6thread6_StateD2Ev(void *s) { }
void _ZNSt12system_errorD1Ev(void *e) { }
void _ZNSt13runtime_errorC2ERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE(void *e, const void *what) { }
void *_ZNSt3_V215system_categoryEv(void) { return (void *)0; }
void *_ZNSt3_V216generic_categoryEv(void) { return (void *)0; }

void _ZdlPv(void *p) { free(p); }
void _ZdlPvm(void *p, unsigned long s) { free(p); }
void *_Znwm(unsigned long s) { return malloc(s); }
void _ZSt20__throw_system_errori(int e) { STUB_ABORT(_ZSt20__throw_system_errori); }
void _ZSt25__throw_bad_function_callv(void) { STUB_ABORT(_ZSt25__throw_bad_function_callv); }

int _dl_find_object(void *pc, void *result) { return -1; }
char *secure_getenv(const char *name) { return (void *)0; }
unsigned long __isoc23_strtoul(const char *s, char **end, int base) { return 0; }
int __cxa_thread_atexit_impl(void (*f)(void *), void *obj, void *dso) { return 0; }
void __cxa_pure_virtual(void) { STUB_ABORT(__cxa_pure_virtual); }
void _ZSt17__throw_bad_allocv(void) { STUB_ABORT(_ZSt17__throw_bad_allocv); }
