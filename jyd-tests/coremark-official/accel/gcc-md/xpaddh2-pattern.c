typedef unsigned int audit_u32;
typedef short audit_s16;
typedef short audit_unaligned_s16 __attribute__ ((aligned (1)));
typedef unsigned int audit_alias_u32
  __attribute__ ((may_alias, aligned (2)));

volatile audit_s16 xpaddh2_audit_sink;

void
packed_halfword_matrix_add (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      data[row * n + column]
	= (audit_s16) ((unsigned short) data[row * n + column]
		       + (unsigned short) addend);
}

void
reject_different_bound (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column + 1 < n; ++column)
      data[row * n + column]
	= (audit_s16) ((unsigned short) data[row * n + column]
		       + (unsigned short) addend);
}

void
reject_post_add_transform (audit_u32 n, audit_s16 *data,
			   audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      data[row * n + column]
	= (audit_s16) (((unsigned short) data[row * n + column]
			+ (unsigned short) addend) ^ 1u);
}

void
reject_narrow_operands (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      data[row * n + column]
	= (audit_s16) ((unsigned char) data[row * n + column]
		       + (unsigned char) addend);
}

void
reject_unused_addend (audit_u32 n, audit_s16 *data,
		      audit_s16 unused_addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      data[row * n + column]
	= (audit_s16) ((unsigned short) data[row * n + column] + 1u);
}

void
reject_weak_alignment (audit_u32 n, audit_unaligned_s16 *data,
		       audit_unaligned_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      data[row * n + column]
	= (audit_unaligned_s16) ((unsigned short) data[row * n + column]
				 + (unsigned short) addend);
}

void
reject_wide_alias (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      {
	audit_alias_u32 *word
	  = (audit_alias_u32 *) &data[row * n + column];
	*word += (unsigned short) addend;
      }
}

void
reject_extra_store (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      {
	audit_s16 result
	  = (audit_s16) ((unsigned short) data[row * n + column]
			 + (unsigned short) addend);
	data[row * n + column] = result;
	xpaddh2_audit_sink = result;
      }
}

void
reject_shifted_address (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      data[row * n + column + 1]
	= (audit_s16) ((unsigned short) data[row * n + column + 1]
		       + (unsigned short) addend);
}

void
reject_inverted_exit (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0;; ++row)
    {
      if (row < n)
	break;
      for (audit_u32 column = 0;; ++column)
	{
	  if (column < n)
	    break;
	  data[row * n + column]
	    = (audit_s16) ((unsigned short) data[row * n + column]
			     + (unsigned short) addend);
	}
    }
}

void
reject_do_while (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  audit_u32 row = 0;
  do
    {
      audit_u32 column = 0;
      do
	{
	  data[row * n + column]
	    = (audit_s16) ((unsigned short) data[row * n + column]
			     + (unsigned short) addend);
	  ++column;
	}
      while (column < n);
      ++row;
    }
  while (row < n);
}

void
reject_third_loop (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 plane = 0; plane < n; ++plane)
    for (audit_u32 row = 0; row < n; ++row)
      for (audit_u32 column = 0; column < n; ++column)
	data[(plane * n + row) * n + column]
	  = (audit_s16)
	    ((unsigned short) data[(plane * n + row) * n + column]
	     + (unsigned short) addend);
}

void
reject_switch_subset (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      switch ((row + column) & 1u)
	{
	case 0:
	  data[row * n + column]
	    = (audit_s16) ((unsigned short) data[row * n + column]
			     + (unsigned short) addend);
	  break;
	default:
	  break;
	}
}

void
reject_volatile_asm (audit_u32 n, audit_s16 *data, audit_s16 addend)
{
  for (audit_u32 row = 0; row < n; ++row)
    for (audit_u32 column = 0; column < n; ++column)
      {
	__asm__ volatile ("" ::: "memory");
	data[row * n + column]
	  = (audit_s16) ((unsigned short) data[row * n + column]
			   + (unsigned short) addend);
      }
}
