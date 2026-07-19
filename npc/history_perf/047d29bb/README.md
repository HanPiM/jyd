# xibei-withMext_clz 16M prefix

This bounded run used the frozen two-cycle low-word multiplier candidate
`047d29bb6d23d46ba2634a27c8448d5c3df8c7e0` and stopped after exactly 16,000,000 committed instructions.

The simulator was built while the candidate was still a dirty change on top of `e99e1f9`, so the embedded Git commit
field in the generated counter files reports `e99e1f9`. The directory name and this note record the actual frozen
candidate identity.

- Cycles: 18,922,412
- CPI: 1.1827
- Baseline cycles: 19,402,270
- Saved cycles: 479,858
- Decision: performance passed, but the candidate was reverted after full-design 280 MHz timing failed at WNS
  -1.515 ns and TNS -643.391 ns.
