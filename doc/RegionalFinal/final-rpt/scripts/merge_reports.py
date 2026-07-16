#!/usr/bin/env python3
"""Build the unified regional-final technical document from the two source reports."""

from pathlib import Path
import re
import shutil


FINAL = Path(__file__).resolve().parents[1]
REGIONAL = FINAL.parent
DESIGN = REGIONAL / "design-rpt" / "src"
VERIFY = REGIONAL / "verify-rpt" / "src"
SRC = FINAL / "src"
ASSETS = SRC / "assets"


def between(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    finish = text.index(end, begin)
    return text[begin:finish].strip()


def demote_sections(text: str) -> str:
    text = text.replace(r"\subsubsection", r"\paragraph")
    text = text.replace(r"\subsection", r"\subsubsection")
    text = text.replace(r"\section", r"\subsection")
    return text


def prefix_images(text: str, prefix: str) -> str:
    pattern = re.compile(r"(\\includegraphics(?:\[[^]]*\])?\{)([^}]+)(\})")
    return pattern.sub(lambda match: f"{match.group(1)}{prefix}/{match.group(2)}{match.group(3)}", text)


def copy_used_assets(source: Path, destination: Path, text: str) -> None:
    pattern = re.compile(r"\\includegraphics(?:\[[^]]*\])?\{([^}]+)\}")
    for relative in sorted(set(pattern.findall(text))):
        logical = relative.split("/", 1)[1] if "/" in relative else relative
        src = source / "assets" / logical
        dst = destination / logical
        if not src.is_file():
            raise FileNotFoundError(f"missing referenced asset: {src}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def main() -> None:
    design = (DESIGN / "main.tex").read_text(encoding="utf-8")
    verify = (VERIFY / "main.tex").read_text(encoding="utf-8")
    if ASSETS.exists():
        shutil.rmtree(ASSETS)

    preamble = design[: design.index(r"\title{")].rstrip()
    design_body = between(design, r"\section{项目概述}", r"\section{设计总结}")
    appendix = between(design, r"\appendix", r"\end{document}")

    board_method = between(verify, r"\section{验证平台说明}", r"\section{验证结果}")
    board_results = between(verify, r"\section{验证结果}", r"\part[仿真报告]")
    simulation_method = between(verify, r"\section{仿真平台介绍}", r"\section{仿真结果}")
    simulation_results = between(verify, r"\section{仿真结果}", r"\section{仿真总结}")

    # The unified chapters provide the top-level headings.
    board_results = board_results.replace(r"\section{验证结果}", "", 1).strip()
    simulation_results = simulation_results.replace(r"\section{仿真结果}", "", 1).strip()
    board_method = demote_sections(board_method)
    simulation_method = demote_sections(simulation_method)

    design_body = prefix_images(design_body, "design")
    appendix = prefix_images(appendix, "design")
    board_method = prefix_images(board_method, "verify")
    board_results = prefix_images(board_results, "verify")
    simulation_method = prefix_images(simulation_method, "verify")
    simulation_results = prefix_images(simulation_results, "verify")
    board_results = board_results.replace("verify/快速预览-1.png", "common/快速预览-1.png")
    board_results = board_results.replace("verify/快速预览-slack.png", "common/快速预览-slack.png")

    common_assets = ["image1.png", "快速预览-1.png", "快速预览-slack.png"]
    for name in common_assets:
        dst = ASSETS / "common" / name
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(DESIGN / "assets" / name, dst)

    copy_used_assets(DESIGN, ASSETS / "design", design_body + "\n" + appendix)
    copy_used_assets(
        VERIFY,
        ASSETS / "verify",
        board_method
        + "\n"
        + board_results.replace("common/快速预览-1.png", "").replace("common/快速预览-slack.png", "")
        + "\n"
        + simulation_method
        + "\n"
        + simulation_results,
    )

    title_and_frontmatter = r'''
\title{基于RISC-V指令集的CPU设计\\竞业达分赛区决赛技术文档}
\author{队伍编号：CICC1004252\\团队名称：和溢位}
\date{}

\begin{document}
\begin{titlepage}
  \centering
  \includegraphics[width=0.35\textwidth]{common/image1.png}\par\vspace{1.2cm}
  {\zihao{2}\bfseries 第十届\\全国大学生集成电路创新创业大赛\par}
  \vspace*{\fill}
  \begin{minipage}{\textwidth}
    \raggedright
    \setlength{\parindent}{0pt}
    \coverfield{报告类型}{分赛区决赛技术文档}
    \vspace{0.45cm}
    \coverfield{参赛杯赛}{``竞业达"企业命题}
    \vspace{0.45cm}
    \coverfield{作品名称}{基于RISC-V指令集的CPU设计}
    \vspace{0.45cm}
    \coverfield{队伍编号}{CICC1004252}
    \vspace{0.45cm}
    \coverfield{团队名称}{和溢位}
    \vspace{2cm}
  \end{minipage}
\end{titlepage}

\tableofcontents
\clearpage

\section*{作品核心内容快速预览}
\addcontentsline{toc}{section}{作品核心内容快速预览}

本项目在初赛 RV32I 五级流水 CPU 的基础上完成分赛区决赛版本迭代。处理器采用
IFU--IDU--EXU--LSU--WBU 五级顺序单发射架构，支持赛题所需的 37 条 RV32I 指令，
并扩展 RV32M、Zicsr 六条 CSR 读改写指令以及 \texttt{ECALL}/\texttt{MRET} 机器态控制流。
设计加入多周期乘除法、2 KiB 直映射 D-cache、基于 FPGA 分布式 RAM 的双副本寄存器堆，
并通过连续取指、16 项 BTB、译码级预计算和关键路径拆分提升频率与有效吞吐率。

工程在 Xilinx Kintex-7 XC7K325T-2FFG900 平台和 Vivado 2024.2 下完成综合、实现与上板验证，
核心频率达到 \textcolor{red}{\textbf{280 MHz}}，布局布线后 WNS 为
\textcolor{red}{\textbf{+0.050 ns}}，无建立时间违例。官方 \texttt{src0}、\texttt{src1}、
\texttt{src2} 的上板运行时间分别为 \textbf{7.101 s、7.047 s、9.233 s}；
\texttt{withoutMext} 与 \texttt{withMext} 分别用时 \textbf{3.890 s、2.020 s}，
硬件 M 扩展获得约 \textbf{1.93 倍}加速。

验证体系由 Vivado Simulation 与 Verilator/C++ 双平台组成，通过 DPI-C 支持停机检测和外设模拟，
以 NEMU 作为差分测试参考模型，并接入 GitHub Actions 持续集成。验证覆盖 RV32I、RV32M、Zicsr、
官方性能程序、CoreMark、Dhrystone 和 RT-Thread 基础负载；仿真预测用时与上板结果一致，
形成从指令级测试、性能量化到数字孪生上板的完整验证闭环。

\begin{figure}[H]
  \centering
  \includegraphics[width=0.96\linewidth]{common/快速预览-1.png}\\[0.5em]
  \includegraphics[width=0.96\linewidth]{common/快速预览-slack.png}
  \caption{分赛区决赛版本核心指标与时序结果}
\end{figure}

\clearpage
'''.strip()

    conclusion = r'''
\clearpage
\section{总结}

本项目完成了一款面向竞业达 FPGA 平台的五级流水 RISC-V CPU，并在初赛版本基础上扩展了
RV32M、Zicsr 与机器态控制流支持。处理器通过 ready/valid 反压、数据旁路、RAW 相关停顿和统一
重定向机制维持顺序提交；多周期乘除法、BRAM 存储、2 KiB D-cache、分布式 RAM 寄存器堆和
16 项 BTB 等设计共同改善了功能覆盖、物理时序和有效吞吐率。

验证工作覆盖指令级单元测试、小规模程序、官方性能测试、CoreMark、Dhrystone、RT-Thread 基础负载
以及五组数字孪生上板用例。Vivado Simulation 与 Verilator/NEMU 差分验证互为补充，自动化流程能够
持续检查正确性并统计周期数、指令数、IPC、CPI、分支预测准确率和 RAW 阻塞率；仿真计算的官方程序
运行时间与上板显示一致，说明验证结果具有较高可信度。

最终工程在 Vivado 2024.2 下达到 280 MHz，WNS 为 +0.050 ns，五组上板程序均运行正确。
结果表明，本项目已形成从体系结构设计、RTL 实现、仿真回归、性能分析、时序收敛到 FPGA 上板的
完整研发闭环，并为后续完善异常中断体系、操作系统支持和更通用的 AXI4 SoC 集成保留了扩展基础。
'''.strip()

    unified = "\n\n".join(
        [
            preamble,
            title_and_frontmatter,
            design_body,
            r"\clearpage" + "\n" + r"\section{验证与测评体系}",
            board_method,
            simulation_method,
            r"\clearpage" + "\n" + r"\section{验证与测评结果}",
            simulation_results,
            board_results,
            conclusion,
            appendix,
            r"\end{document}",
        ]
    ) + "\n"

    SRC.mkdir(parents=True, exist_ok=True)
    (SRC / "main.tex").write_text(unified, encoding="utf-8")


if __name__ == "__main__":
    main()
