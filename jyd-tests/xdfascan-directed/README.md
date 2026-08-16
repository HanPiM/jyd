# XDFA whole-string scan directed test

`xdfascan` uses custom-2, `funct3=5`, `funct7=3`. It takes a runtime byte
pointer in `rs2`, updates the existing numeric-DFA transition and final-state
counters, and returns the address of the first NUL in `rd`.

Each invocation starts in DFA state 0 and processes bytes in increasing address
order. Every non-NUL byte is consumed exactly once. A comma commits the current
token and starts a new one; an invalid transition consumes the offending byte,
commits the invalid token, and starts a new token at the following byte. The
first NUL is read but is neither consumed nor presented to the DFA. A preceding
nonempty token is committed once, an empty trailing token is not committed, and
the returned pointer addresses that NUL. Counter arithmetic is modulo 2^32.

The sole input precondition is that a readable NUL terminator is reachable.
There is no architectural seed, iteration-count, data-size, alignment,
string-length, source-symbol, or filename condition.

Run the NPC/NEMU semantic comparison with:

```sh
make ARCH=riscv32-jyd run
```
