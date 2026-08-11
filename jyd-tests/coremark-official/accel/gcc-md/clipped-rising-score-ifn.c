/* Direct IFN-to-MD smoke test.  Ordinary C selection is tested separately. */
unsigned int __GIMPLE ()
clipped_rising_score_ifn (unsigned int *base, unsigned int count,
                          unsigned int clip)
{
  unsigned int result;
  result_1 = .CLIPPED_RISING_SCORE_REDUCE (base, count, clip);
  return result_1;
}
