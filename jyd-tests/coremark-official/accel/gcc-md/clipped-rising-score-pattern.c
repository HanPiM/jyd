typedef unsigned int u32;
typedef short s16;

#define REDUCE_BODY(NAME, THRESHOLD, RESET, BONUS, RISING)                 \
s16 NAME (u32 count, int *values, s16 clip)                               \
{                                                                         \
  int sum = 0;                                                            \
  int previous = 0;                                                       \
  s16 score = 0;                                                          \
  for (u32 row = 0; row < count; ++row)                                  \
    for (u32 column = 0; column < count; ++column)                       \
      {                                                                   \
        int current = values[row * count + column];                       \
        sum += current;                                                   \
        if (sum THRESHOLD clip)                                           \
          {                                                               \
            score += BONUS;                                               \
            sum = RESET;                                                  \
          }                                                               \
        else                                                              \
          score += current RISING previous;                               \
        previous = current;                                               \
      }                                                                   \
  return score;                                                           \
}

REDUCE_BODY (clipped_rising_score, >, 0, 10, >)
REDUCE_BODY (reject_equal_threshold, >=, 0, 10, >)
REDUCE_BODY (reject_nonzero_reset, >, 1, 10, >)
REDUCE_BODY (reject_different_bonus, >, 0, 9, >)
REDUCE_BODY (reject_falling_score, >, 0, 10, <)
