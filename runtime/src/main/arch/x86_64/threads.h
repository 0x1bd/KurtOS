#ifndef KURTOS_THREADS_H
#define KURTOS_THREADS_H

#include <stdint.h>

void kthread_init(void);
void kthread_yield(void);
uint64_t kthread_self(void);

int kthread_create(uint64_t *out_id, void *(*start)(void *), void *arg);
int kthread_join(uint64_t id, void **retval);
int kthread_detach(uint64_t id);
void kthread_exit(void *retval);

#endif
