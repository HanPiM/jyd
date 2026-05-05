# src0 Performance Test Report

## Summary

`src0.bin` contains a second-stage performance test dispatcher named `run_perf_test_stage`.
Its behavior is:

- show the input value on the seven-segment display
- start the hardware counter
- run three correctness-checked workloads
- stop the counter
- display elapsed time on the seven-segment display
- show pass/fail on the LED matrix

The function returns `0` in both success and failure paths; pass/fail is communicated through MMIO side effects.

## Entry And Display Behavior

The performance stage begins by calling:

- `show_input_on_seg()`
- `counter_start_pulse10()`

`show_input_on_seg()` reads `g_dram_input` at `0x80100000`, converts it to packed decimal nybbles with `pack_decimal_digits()`, and writes the packed digits into the high byte lanes of `MMIO_SEG`.

`counter_start_pulse10()` writes `0x80000000` to `MMIO_COUNTER` ten times.
This strongly suggests the hardware timer is edge or pulse triggered, so the firmware emits the command repeatedly for robustness.

## Workload 1: Matrix Multiplication Cross-Check

The first loop runs 10 iterations with `matrix_iter = 0..9`.
In each iteration:

- `fill_indexed_90x90(0x801138c4, matrix_iter)` initializes matrix A
- `fill_indexed_90x90(0x8011b754, matrix_iter << 1)` initializes matrix B
- `matrix_mul_90x90(0x801138c4, 0x8011b754, 0x801235e4)` computes one result
- `matrix_mul_reference_or_relayout(0x801138c4, 0x8011b754, 0x8012b474)` computes a second result
- `matrix_eq_90x90(0x801235e4, 0x8012b474)` checks whether the two outputs match

`fill_indexed_90x90()` fills a `90 x 90` matrix with:

- `buf[row * 90 + col] = ((row * col) + seed) mod 100`

`matrix_mul_90x90()` is a standard triple-loop matrix multiply over `90 x 90 x 90`.

The second matrix helper at `0x578`, currently named `matrix_mul_reference_or_relayout`, is large and likely serves as a reference implementation, alternate layout implementation, or rearrangement-based validation path.
What is stable is its role in producing a second matrix result for comparison.

If any of the 10 matrix checks fail, the sample immediately jumps to the failure-report path.

## Workload 2: Sorting Cross-Check

The second loop also runs 10 iterations with `sort_iter = 0..9`.
In each iteration:

- `fill_linear_u32(0x80133304, 1000, sort_iter)` fills a 1000-element input buffer
- the buffer is copied element-by-element to `0x801342a4`
- `bubble_sort_u32(0x80133304, 1000)` sorts the first copy
- `selection_sort_u32(0x801342a4, 1000)` sorts the second copy
- `array_eq_u32(0x80133304, 0x801342a4, 1000)` compares the sorted outputs

`fill_linear_u32()` fills each element with:

- `(count * 0x49 - seed * 0x1f + index) mod 0x3037`

So this stage is not benchmarking two different data sets; it is benchmarking two different sorting implementations on the same generated array.

If any comparison fails, the sample immediately jumps to the failure-report path.

## Workload 3: Prime Counting Cross-Check

After the matrix and sorting stages both pass, the sample calls `prime_test_stage()`.

This function performs two independent prime-counting methods up to `0x4e20` (`20000` decimal), then compares their counts:

1. Trial-division style count
   - iterate `n = 2..20000`
   - call `is_prime_like(n)`
   - increment `trial_prime_count` when prime

2. Sieve-style count
   - initialize a DRAM-backed mark array to `1`
   - explicitly clear one early slot via `_DAT_80100038 = 0`
   - iterate `prime = 2..20000`
   - if current entry is nonzero, count it as prime
   - mark all multiples starting from `prime << 1` as composite

`prime_test_stage()` returns true only when:

- `sieve_prime_count == trial_prime_count`

So this stage is another correctness cross-check between two implementations, not a pure throughput-only microbenchmark.

## Pass/Fail Reporting

On failure, the sample calls `report_perf_fail()`:

- `counter_stop_pulse10()`
- `read_counter()`
- `or_decimal_digits_to_seg(value)`
- `build_led_fail_pattern()`

On success, the sample calls `report_perf_pass()`:

- `counter_stop_pulse10()`
- `read_counter()`
- `or_decimal_digits_to_seg(value)`
- `build_led_pass_pattern()`

This means the lower 24 bits of `MMIO_SEG` are used to append the measured elapsed time, while the LED output encodes pass or fail visually.

## Conclusions

The `src0` performance test is best described as a correctness-guarded benchmark stage with three workloads:

- matrix multiplication validation
- sorting validation
- prime-counting validation

It does not trust a single implementation.
Each workload computes the same logical result through two different methods and aborts on the first mismatch.
Only after all checks pass does it stop the timer, show the elapsed time, and emit the pass LED pattern.
