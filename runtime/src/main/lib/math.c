#include <stdint.h>

int isnan(double x) { return x != x; }
int isinf(double x) { return !isnan(x) && isnan(x - x); }
int __isnan(double x) { return isnan(x); }
int __isinf(double x) { return isinf(x); }

double fabs(double x) { return x < 0 ? -x : x; }
float fabsf(float x) { return x < 0 ? -x : x; }

double floor(double x) {
    double t = (double)(long long)x;
    return (x < 0 && t != x) ? t - 1 : t;
}

double ceil(double x) {
    double t = (double)(long long)x;
    return (x > 0 && t != x) ? t + 1 : t;
}

double trunc(double x) { return (double)(long long)x; }
double rint(double x) { return (double)(long long)(x < 0 ? x - 0.5 : x + 0.5); }
double round(double x) { return rint(x); }

double fmod(double a, double b) {
    if (b == 0.0) return 0.0 / 0.0;
    return a - (double)(long long)(a / b) * b;
}

double remainder(double a, double b) { return fmod(a, b); }

double sqrt(double x) {
    double r;
    __asm__("sqrtsd %1, %0" : "=x"(r) : "x"(x));
    return r;
}

float sqrtf(float x) {
    float r;
    __asm__("sqrtss %1, %0" : "=x"(r) : "x"(x));
    return r;
}

float floorf(float x) { return (float)floor(x); }
float ceilf(float x) { return (float)ceil(x); }
float truncf(float x) { return (float)trunc(x); }
float roundf(float x) { return (float)round(x); }
float rintf(float x) { return (float)rint(x); }
float fmodf(float a, float b) { return (float)fmod(a, b); }
float remainderf(float a, float b) { return (float)fmod(a, b); }

#define UNSUPPORTED_D(name) double name(double x) { (void)x; return 0.0 / 0.0; }
#define UNSUPPORTED_F(name) float name(float x) { (void)x; return 0.0f / 0.0f; }
#define UNSUPPORTED_D2(name) double name(double a, double b) { (void)a; (void)b; return 0.0 / 0.0; }
#define UNSUPPORTED_F2(name) float name(float a, float b) { (void)a; (void)b; return 0.0f / 0.0f; }

UNSUPPORTED_D(sin) UNSUPPORTED_D(cos) UNSUPPORTED_D(tan)
UNSUPPORTED_D(asin) UNSUPPORTED_D(acos) UNSUPPORTED_D(atan)
UNSUPPORTED_D(sinh) UNSUPPORTED_D(cosh) UNSUPPORTED_D(tanh)
UNSUPPORTED_D(asinh) UNSUPPORTED_D(acosh) UNSUPPORTED_D(atanh)
UNSUPPORTED_D(exp) UNSUPPORTED_D(expm1) UNSUPPORTED_D(log)
UNSUPPORTED_D(log10) UNSUPPORTED_D(log2) UNSUPPORTED_D(log1p)
UNSUPPORTED_D(cbrt)
UNSUPPORTED_D2(pow) UNSUPPORTED_D2(atan2) UNSUPPORTED_D2(hypot)
UNSUPPORTED_D2(nextafter)

UNSUPPORTED_F(sinf) UNSUPPORTED_F(cosf) UNSUPPORTED_F(tanf)
UNSUPPORTED_F(asinf) UNSUPPORTED_F(acosf) UNSUPPORTED_F(atanf)
UNSUPPORTED_F(sinhf) UNSUPPORTED_F(coshf) UNSUPPORTED_F(tanhf)
UNSUPPORTED_F(asinhf) UNSUPPORTED_F(acoshf) UNSUPPORTED_F(atanhf)
UNSUPPORTED_F(expf) UNSUPPORTED_F(expm1f) UNSUPPORTED_F(logf)
UNSUPPORTED_F(log10f) UNSUPPORTED_F(log2f) UNSUPPORTED_F(log1pf)
UNSUPPORTED_F(cbrtf)
UNSUPPORTED_F2(powf) UNSUPPORTED_F2(atan2f) UNSUPPORTED_F2(hypotf)
UNSUPPORTED_F2(nextafterf)

double ldexp(double x, int e) {
    double r = x;
    while (e > 0) { r *= 2.0; e--; }
    while (e < 0) { r /= 2.0; e++; }
    return r;
}
