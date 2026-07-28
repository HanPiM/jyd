# withMext-v2 仿真数据说明

`withmext-v2-summary.csv` 是后续优化报告采用的统一性能数据。所有列出的版本均运行同一份
`withMext-v2` 程序至正常结束，时间统一按 `cycle_count / 280 MHz` 换算。

`xibei-summary.csv` 保留西北赛区样例的关键里程碑数据。该程序的动态指令组成和规模与
`withMext-v2` 不同，因此只在文件内部逐行比较，不与 `withmext-v2-summary.csv` 横向比较。

- 程序：`withMext-v2.bin`
- SHA-256：`b998eae7b2e6c3dd5911bc67927cea4e9c4f8a1bcb639391c520ba3391742749`
- 数据文件：`withMext-v2.data.bin`
- SHA-256：`2ab9a4c9e7c2cc1b785637f6df83d1cbe0b175c838b478dedbfd30516de24e80`
- 仿真环境：Verilator 周期级 RTL 仿真，`MAKE_PERF=1`，关闭 difftest、反汇编和异常跟踪
- 完整运行的动态指令数：380,344,384

WNS 取自既有优化实验记录。更换仿真程序不会改变综合、布局布线或 WNS，因而未重复运行 Vivado。
空白 WNS 表示该提交只作为性能采样点，报告表格会与相邻、周期相同的时序整理项合并展示。

批量复现实验可运行 `scripts/run-withmext-v2-matrix.sh`。脚本在 `/tmp` 创建隔离的共享克隆，先做
5 秒启动检查，再并行运行完整仿真；`START_INDEX`、`END_INDEX` 和 `MAX_PARALLEL` 可控制范围与并发数。
本次原始日志保留在：

- `/tmp/jyd-withmext-v2-bench.UcWLD6`
- `/tmp/jyd-withmext-v2-bench.ZV5R6X`
