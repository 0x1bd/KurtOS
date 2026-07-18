__attribute__((amdgpu_kernel))
void gradient(unsigned int *fb, unsigned int width, unsigned int height, unsigned int pitchpx)
{
    unsigned int gid = __builtin_amdgcn_workgroup_id_x() * 64u + __builtin_amdgcn_workitem_id_x();
    if (gid >= width * height) return;
    unsigned int x = gid % width;
    unsigned int y = gid / width;
    unsigned int r = (x * 255u) / width;
    unsigned int g = (y * 255u) / height;
    fb[y * pitchpx + x] = (r << 16) | (g << 8) | 0x60u;
}
