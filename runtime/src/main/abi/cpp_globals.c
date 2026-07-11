char __libc_single_threaded = 1;
void *__dso_handle = 0;
void * _ZTISt12system_error[4] = {0};
void * _ZTVSt12system_error[4] = {0};
void * _ZTINSt6thread6_StateE[4] = {0};

typedef struct {
    unsigned char rehash;
    unsigned long bucket_count;
} cpp_rehash_decision_t;

cpp_rehash_decision_t _ZNKSt8__detail20_Prime_rehash_policy14_M_need_rehashEmmm(
    void *policy,
    unsigned long bucket_count,
    unsigned long element_count,
    unsigned long insert_count
) {
    (void)policy;
    (void)element_count;
    (void)insert_count;

    cpp_rehash_decision_t decision = {0, bucket_count};
    return decision;
}
