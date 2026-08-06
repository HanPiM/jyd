# coe_replace — replace IROM/DRAM contents inside an existing bitstream

## 结论（2026-08-05 已上板验证）

“替换 bit 内程序而不重新跑 Vivado 实现”是可行的，但数据**不是**以 COE 明文
存放在 bit 里，而是按 BRAM 配置帧（INIT/INITP）重排后的二进制。只要拿到与
该 bit 同一实现的 routed/post-route DCP，就可以精确地：

1. 从新 COE 计算每个 IROM/DRAM BRAM 单元的 `INIT_xx` / `INITP_xx`；
2. 把属性写进 DCP 副本；
3. `write_bitstream` 生成新 bit（不重跑 place/route）。

验证过程：

- 基准 bit：`/srv/data/jyd/archive/coremark-uart-fixed-280-20260805/
  perf3-0925488-btb256-ras/top.bit`
  （SHA-256 `ba66bf67...`，五块板 CoreMark 11.374198420 s 全部有效）。
- 替换程序：`uartprint`（打印 10 行 “The quick brown fox...” 和
  `UARTPRINT_DONE`）。
- FPGA13 上板结果：串口完整输出 `UARTPRINT_DONE`，退出码 0。
- 自校验：把原始 COE 重新写回 DCP 后生成的 bit 与原 bit 仅差 4 字节头部
  时间戳 + 少量因 COE 文件截断造成的帧位（约 30 字节）。

## RTT Nano + 内嵌 CoreMark 示例（2026-08-06 上板验证）

`example/` 下提供当前 300 MHz 候选（DCache 4KB + pblock，WNS -0.368，
上板 10.707474300 s）对应的两个产物：

- `original.bit`：当前 300 MHz 独立 CoreMark bit
  （SHA-256 `c5028d2b...`，CoreMark 10000 次 10.707474300 s，CRC 0x988c；
  由 `opt-300-dcache4k@d23a4ff` 的 DCP `d3193cff...` + COE 90bb/0710 生成）。
- `rttnano_coremark.bit`：RTT Nano + CoreMark 替换 bit
  （SHA-256 `7ae61df9...`，2026-08-06 启用 msh 完整行编辑后重新生成；
  旧 simple-editor 版本已存档为
  `/srv/data/jyd/archive/rttnano_coremark-simple-editor-20260806.bit`；源 COE 见
  `jyd-tests/rtthread-nano/build/rtthread-nano-riscv32-jyd.{text,data}.coe`，
  data COE 含 10000 次迭代）。该 RTT 内嵌 CoreMark 与独立版逐对象同标志
  编译（`COREMARK_OPT=-Os` + 源文件 HOT/COLD O3 属性，支持 TU 同 B 扩展
  march、伪浮点、同报告串 `-O2 -O3 -Os -march=rv32im_zicsr_zba_zbb_zbc_zbs
  -mabi=ilp32`），FPGA13 上板 `version`/`coremark` 正常，
  `Total time 10.706997880000 s`，与独立版 10.707474300 s 一致。

历史 280 MHz perf3 示例（original `ba66bf67...` / rttnano `94b01881...`）
已被上述 300 MHz 产物替代；perf3 11.374198420 s 的复现说明见下节。

RTT 上板交互验证（FPGA13）：启动后进入 `msh >`，手动输入 `coremark`，
完整跑完 10000 iterations：

```text
Total ticks      : 577265660
Total time (secs): 11.545313200000
Iterations/Sec   : 866.152336170490
Compiler flags   : -O3 -march=rv32im_zicsr_zba_zbb_zbc_zbs
Correct operation validated.
```

即：不重跑实现，只替换 IROM/DRAM COE 内容生成的 bit 可以正常启动 RTT，
并且能通过串口交互运行内嵌 CoreMark。

## 11.374 s 独立版的精确复现（2026-08-06）

当前仓库源码可以直接重建 perf3 11.374198420 s 用的独立版 CoreMark 镜像
（ELF/bin/COE 哈希逐字节一致），条件是 `RISCV_ZEXTS=_zba_zbb_zbc_zbs`
（带前导下划线，报告里显示为 `rv32im_zicsr__zba...`）。用这组 COE 重新
生成 bit 后上板，结果与历史完全一致：`568709921 ticks / 11.374198420 s /
879.18 it/s / CRC 全对`。构建命令和哈希见
`/srv/data/jyd/archive/coe-replace-20260805/README.md`。

对比 RTT 内嵌版（`577265660 ticks / 11.5453132 s / 866.15 it/s`），
~1.5% 的性能差距主要不是 RTT 运行环境，而是编译优化级别不同：独立版 perf3
实际全部按 `-Os` 编译（其 Makefile 声称的 benchmark TU -O3 规则并不存在），
而 RTT 内嵌版按 `-O3` 编译（`COREMARK_OPT=-O3`）。对照实验：

- 独立版 -O3：`577581020 ticks / 11.5516204 s`，与 RTT -O3 基本一致；
- RTT -Os：`569560579 ticks / 11.39121158 s`，只比独立版 -Os 慢 0.15%。

所以 RTT 自身开销很小，主要差异来自 CoreMark 的 -O3 代码（尤其
`core_state_transition` 的分支结构）在该 CPU 上比 -Os 慢。详细分析见
`/srv/data/jyd/archive/coe-replace-20260805/README.md`。

2026-08-06 已按原定设计修复：`jyd-tests/coremark-official/Makefile` 补上
`COREMARK_OPT ?= -O3`，只对 5 个核心 benchmark TU 生效，其余 TU 保持
`-Os`（与 RTT 侧规则一致）。因此上述 11.374 s 的“逐字节复现”使用修复前
Makefile 才成立；修复后默认独立版构建为“核心 -O3、其余 -Os”。

## 为什么不能简单地“改 COE 明文”

COE/MIF 对应的二进制在 bit 里按 7 系列 BRAM 初始化帧存放。例如 IROM 的
`ramloop[2..5]` 是 4096x9 原语（8 数据位 + 1 parity 位），parity 位实际存放
指令的第 14/23 位（INITP）；如果只改 INIT 不改 INITP，程序会损坏（上板输出
全 0）。这也说明替换必须按单元精确重算 INIT/INITP，而不是全文搜索替换。

## 直接改 bit 的可能性

用“单 bit 探针”验证过：在 DCP 里翻转某个 `INIT_00` 的第 0 位再写 bitstream，
与原 bit 对比，**bit 文件里恰好只有 1 个配置帧数据位变化**（外加文件末尾的
全局 CRC）。因此 bit 内差异与 COE 二进制差异存在 1:1 对应。

帧映射实测（2026-08-05）：

- 对 72 个 BRAM 单元各翻转 `INIT_00` 第 0 位并重新写 bitstream，与原 bit
  对比，每个翻转**恰好改变 1 个配置帧数据位**（外加文件末尾的全局 CRC），
  即 COE 二进制位与 bit 流帧位存在 1:1 对应。
- 每个单元在该帧内的基准位置（帧号、帧内字、字内 bit）由 BRAM 放置位置
  决定，72 个单元的基准表已归档在
  `/srv/data/jyd/archive/coe-replace-20260805/bram_cell_frame_map.json`。
- 同一单元内不同 INIT 位的字内排列不是简单线性映射（含位重排），要做
  纯二进制补丁脚本需要把每个单元 256 个 INIT 位的完整映射表也标定出来；
  探针方法可以逐位标定（每个单元 256 次探针，可一次性批量完成）。

因此“直接改 bit”在原理上可行，但工程上最稳妥、已上板验证的做法仍是
DCP+INIT+write_bitstream（结果与直接改帧等价）。

## 用法

```bash
python3 coe_replace.py \
  --dcp /path/to/top_postroute_physopt.dcp \
  --irom-coe /path/to/irom.coe \
  --dram-coe /path/to/dram.coe \
  --out /path/to/top_new.bit \
  --bit /path/to/original.bit     # 可选：打印变更报告
```

依赖：Vivado 2024.2（能打开该 DCP 即可）、Python 3 标准库。

脚本只支持当前 digital_twin 结构：

- IROM：8 个单元（1x RAMB18 2-bit + 1x RAMB36 4-bit + 6x RAMB36 8/9-bit）
- DRAM：64 个 RAMB36 8-bit 单元（16 bank × 4 字节通道）

其它结构会明确报错，不会静默产出错误 bit。

## 校验建议

1. 先跑一次 `--bit 原bit`，确认“变更字节数”合理（正常应只有头部时间戳 +
   BRAM 初始化帧 + 末尾 CRC）。
2. 上板抓串口（unattended 用 `capture`），确认新程序输出。
