# RT-Thread Nano AM port

This test runs upstream [RT-Thread Nano 3.1.5](https://github.com/RT-Thread/rtthread-nano/tree/v3.1.5)
on the JYD Abstract Machine platform. The v3.1.5 commit is pinned in
`SOURCE_COMMIT`; `make` checks it out under the ignored `upstream/` directory.

The AM port supplies cooperative context switching and console input/output.
JYD does not implement CLINT, so the port does not rely on periodic timer
interrupts. It starts a minimal FinSH/msh terminal implementing the required
`ps`, `version`, `list_thread`, and `list_semaphore` commands. Enter `help` to
list commands. Reaching the `msh >` prompt is
the runtime success criterion, and the simulation must then be stopped manually.

The image embeds a 10,000-iteration CoreMark workload as the `coremark` command.
See [COREMARK.md](COREMARK.md) for compiler-option boundaries and how to switch
to the competition-provided benchmark sources.

From the repository root:

```sh
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd run
```

The shell uses RT-Thread's size-optimized `rt_kprintf`; CoreMark retains its
`%f`-capable EEMBC formatter but shares the same RTT console backend. Klib
printf is not linked. AM `try_getch()` supplies terminal input directly,
without the RT-Thread device or POSIX stdio layers.

For an interactive NPC run, the simulated UART switches a TTY stdin from
canonical line input to character input on its first read. Tab and escape
sequences from arrow keys therefore reach msh immediately without waiting for
Enter. Redirected and piped stdin are left unchanged, and the simulator restores
the TTY on normal exit, SIGINT, SIGTERM, or SIGHUP.

Use `make -C jyd-tests/rtthread-nano fetch` to materialize or refresh only the
pinned upstream checkout.

Size-reduction decisions, measurements, validation results, and pending work
are maintained in [OPTIMIZATION.md](OPTIMIZATION.md). Read it before making
further RT-Thread Nano configuration or footprint changes.

## Debug with Renode

Build the unoptimized image and start Renode's GDB server in one terminal:

```sh
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd gdb-server
```

Connect from another terminal:

```sh
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd gdb-client
```

The JYD Renode platform loads the executable image at `0x80000000` and its
separate AM data image at `0x80100000`, matching the NPC simulation layout.
The server listens on port 3333 by default.

For scripted debugging, pass GDB options through `renode/run-gdb.sh gdb`; this
keeps architecture selection and connection setup in the shared wrapper.
