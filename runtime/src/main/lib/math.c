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

double ldexp(double x, int e) {
    double r = x;
    while (e > 0) { r *= 2.0; e--; }
    while (e < 0) { r /= 2.0; e++; }
    return r;
}
