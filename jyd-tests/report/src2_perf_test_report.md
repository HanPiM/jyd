# src2 性能测试报告

## 概述

`src2.bin` 的性能测试入口为 `run_perf_test_stage()`。
这一阶段的总体流程是：

- 在七段数码管上显示输入值
- 启动硬件计数器
- 依次执行三个带正确性校验的工作负载
- 任一工作负载失败时立即停止并上报失败
- 全部通过后停止计数器、显示耗时，并输出通过图案

因此，`src2` 的性能测试不是脱离结果验证的裸 benchmark，而是一个带正确性门禁的性能测试阶段。

## 入口与显示行为

性能阶段开始时会先调用：

- `show_input_on_seg()`
- `counter_start_pulse10()`

`show_input_on_seg()` 会读取 `0x80100000` 处的输入值，将其转换为压缩十进制数字后写入 `MMIO_SEG`。

`counter_start_pulse10()` 会向 `MMIO_COUNTER` 连续写入 10 次 `0x80000000`，用于启动计时。

## 工作负载 1：链表构造与加权求和校验

第一部分调用 `linked_list_stage()`。
这一阶段会对 `seed = 0..499` 逐轮执行同一组校验：

- `build_weighted_list_2000(seed, 0x80101030)` 构造第一份链表
- `build_weighted_list_2000(seed, 0x80104eb0)` 构造第二份链表
- `build_weighted_list_2000(seed + 1, 0x80108d30)` 构造第三份链表
- `sum_weighted_list_forward()` 分别计算三份链表的加权和
- `list_length()` 检查链表长度是否固定为 `2000`
- `sum_weighted_list_reverse_copy()` 对其中一份链表做逆序复制后的加权求和

这一阶段要求同时满足以下条件：

- 相同 `seed` 生成的两份链表，其前向加权和完全一致
- 不同 `seed` 生成的链表，其前向加权和必须不同
- 链表长度必须恒为 `2000`
- 正向加权求和与逆序复制后的加权求和结果一致

链表节点的值不是简单递增序列，而是由以下模式生成：

- 首节点值为 `(seed * 13 + 7) mod 1000`
- 后续节点值为 `((seed * index) * 37 + index * index) mod 500`

其中乘法和取模使用样本内的软实现 `shift_add_mul_u32()`、`udiv32()`、`umod32()`、`smod32()` 完成。

## 工作负载 2：CRC 翻转一致性校验

第二部分调用 `crc_flip_consistency_test()`。
该阶段会对 `seed = 0..199` 重复执行以下流程：

- `fill_crc_test_buffer(seed)` 初始化 `0x80100030` 起始的 `0x1000` 字节缓冲区
- `crc16_modbus()` 计算原始缓冲区 CRC
- 选中一个位置，将该字节按位异或 `0x5a`
- 再次计算 CRC
- 对同一位置再次异或 `0x5a`，恢复原始内容
- 第三次计算 CRC

缓冲区填充值公式为：

- `buffer[index] = index * 0x11 + seed * 0x17 + 0x1d`

被翻转的位置为：

- `flip_offset = (seed * 0x7b) mod 0x1000`

这一阶段通过的条件是：

- 第一次 CRC 与翻转后 CRC 必须不同
- 恢复缓冲区后的 CRC 必须与第一次 CRC 完全相同

因此，这部分不是单纯测量 CRC 吞吐，而是在反复验证“单字节扰动可检测、恢复后校验值可复现”的一致性。

## 工作负载 3：90x90 矩阵乘法重复实现校验

第三部分执行一组 `90 x 90` 矩阵运算：

- `fill_indexed_90x90(0x80114a40, 1)` 初始化矩阵 A
- `fill_indexed_90x90(0x8011c8d0, 2)` 初始化矩阵 B
- `matrix_mul_90x90_with_rhs_transpose(0x80114a40, 0x8011c8d0, 0x80124760)` 计算第一份输出
- 再次调用 `matrix_mul_90x90_with_rhs_transpose(0x80114a40, 0x8011c8d0, 0x8012c5f0)` 计算第二份输出
- `matrix_eq_90x90(0x80124760, 0x8012c5f0)` 比较两份结果

矩阵填充规则为：

- `matrix[row * 90 + col] = (row + col + bias) mod 50`

矩阵乘法实现包含两个步骤：

- 先将右矩阵转置到临时缓冲区
- 再执行标准三重循环乘加

这里的目标不是比较两种不同算法，而是对同一实现做两次独立输出，并检查结果是否一致。
只有两份 `90 x 90` 输出矩阵完全相同时，性能阶段才会进入成功路径。

## 通过/失败上报

失败路径调用 `report_perf_fail()`，成功路径调用 `report_perf_pass()`。
两者的共同模式是：

- `counter_stop_pulse10()`
- `read_counter()`
- `or_decimal_digits_to_seg(value)`

区别在于最后输出到 LED 的图案不同：

- 失败时调用 `write_led_fail_pattern()`
- 成功时调用 `write_led_pass_pattern()`

因此，`src2` 的性能测试结果通过两种 MMIO 侧效果体现：

- 七段数码管显示计时值
- LED 点阵显示通过/失败图案

## 结论

`src2` 的性能测试可以概括为三个串行的正确性保护工作负载：

- 链表构造与加权求和校验
- CRC 翻转一致性校验
- `90 x 90` 矩阵乘法重复实现校验

它的特点不是只追求长时间运行，而是每个工作负载都必须先证明结果自洽，再允许整个性能阶段继续。
也就是说，`src2` 的“性能测试”本质上是“带结果门禁的性能测试”，而不是脱离正确性的纯吞吐测试。
