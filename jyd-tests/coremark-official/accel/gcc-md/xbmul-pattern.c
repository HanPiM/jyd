unsigned
packed_field_multiply(unsigned value)
{
    return ((value >> 2) & 0xfu) * ((value >> 5) & 0x7fu);
}
