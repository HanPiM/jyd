typedef signed short s16;

s16 duplicate_byte_one(s16 value) {
  return (s16)((value & (s16)0xff00) | ((value >> 8) & 0xff));
}

s16 renamed_duplicate_byte_one(s16 sample) {
  return (s16)((sample & (s16)0xff00) | ((sample >> 8) & 0xff));
}

s16 reject_wrong_shift(s16 value) {
  return (s16)((value & (s16)0xff00) | ((value >> 7) & 0xff));
}

s16 reject_wrong_mask(s16 value) {
  return (s16)((value & (s16)0xfe00) | ((value >> 8) & 0xff));
}

s16 reject_shift_value_live(s16 value, unsigned int *extra) {
  unsigned int shifted = (unsigned short)value >> 8;
  *extra = shifted;
  return (s16)((value & (s16)0xff00) | shifted);
}

s16 reject_mask_value_live(s16 value, s16 *extra) {
  s16 masked = value & (s16)0xff00;
  *extra = masked;
  return (s16)(masked | ((value >> 8) & 0xff));
}
