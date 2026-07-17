__attribute__((amdgpu_kernel))
void fill(unsigned int *out)
{
    out[__builtin_amdgcn_workitem_id_x()] = 0xCA11AB1Eu;
}
