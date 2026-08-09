#!/usr/bin/env bash

set -euo pipefail

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
test_files="$script_dir/xsim-seg37"
coe_dir="${COE_DIR:-$repo_root/jyd-vivado-proj/digital_twin.srcs/sources_1/imports/cur_coe}"
timeout_seconds="${TIMEOUT_SECONDS:-60}"
vivado_bin="${VIVADO:-vivado}"
vivado_root="${VIVADO_ROOT:-/home/tools/Xilinx/Vivado/2024.2}"
expected_div_cpd="${EXPECTED_DIV_CPD:-1}"
expected_div_latency="${EXPECTED_DIV_LATENCY:-34}"
expected_div_tready="${EXPECTED_DIV_TREADY:-0}"

if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "TIMEOUT_SECONDS must be a positive integer: $timeout_seconds" >&2
  exit 2
fi
if [ ! -f "$coe_dir/irom.coe" ] || [ ! -f "$coe_dir/dram.coe" ]; then
  echo "COE_DIR must contain irom.coe and dram.coe: $coe_dir" >&2
  exit 2
fi
if ! command -v "$vivado_bin" >/dev/null 2>&1; then
  echo "Vivado executable not found: $vivado_bin" >&2
  exit 2
fi

jyd_data_root="${JYD_DATA_ROOT:-/srv/data/jyd}"
# TMPDIR is inherited by Vivado and child tools. Under Codex keep it under
# /srv/data/jyd/tmp; /tmp can trigger cache/temp write denials.
run_root="${TMPDIR:-$jyd_data_root/archive}"
mkdir -p -- "$run_root"
work_dir=$(mktemp -d "$run_root/jyd-xsim-seg37.XXXXXX")
echo "SEG37_WORK_DIR=$work_dir"
echo "SEG37_COE_DIR=$coe_dir"
echo "SEG37_DIV_IMPLEMENTATION=rtl-iterative retained_fallback_ip=div_gen_uradix2"
sha256sum "$coe_dir/irom.coe" "$coe_dir/dram.coe"

# Refresh only npc/build/pack-fpga.  The in-tree Vivado project, generated IP
# products, implementation runs, and bitstreams are never opened or modified.
make -C "$repo_root/npc" pack-fpga

"$vivado_bin" -mode batch -nolog -nojournal \
  -source "$test_files/generate_ips.tcl" \
  -tclargs "$repo_root" "$work_dir" "$coe_dir" \
  >"$work_dir/generate-ips.log" 2>&1

ip_project=$(find "$work_dir" -type d -path '*/sources_1/ip' -print | while IFS= read -r candidate; do
  if [ -f "$candidate/div_gen_uradix2/sim/div_gen_uradix2.vhd" ]; then
    echo "$candidate"
    break
  fi
done)
if [ -z "$ip_project" ]; then
  echo "Could not locate generated IP simulation products; see $work_dir/generate-ips.log" >&2
  exit 1
fi
mapfile -t ip_verilog < <(find "$work_dir" -type f \
  \( -path '*/hdl/*.v' -o -path '*/sim/*.v' -o -path '*/simulation/*.v' \) -print | sort)
mapfile -t ip_vhdl < <(find "$work_dir" -type f -path '*/sim/*.vhd' -print | sort)
mapfile -t rtl_sources < <(find "$repo_root/npc/build/pack-fpga" -type f \( -name '*.v' -o -name '*.sv' \) -print | sort)

if [ "${#ip_verilog[@]}" -eq 0 ] || [ "${#ip_vhdl[@]}" -eq 0 ] || [ "${#rtl_sources[@]}" -eq 0 ]; then
  echo "Missing generated simulation sources; see $work_dir/generate-ips.log" >&2
  exit 1
fi

divider_model="$ip_project/div_gen_uradix2/sim/div_gen_uradix2.vhd"
echo "# Divider configuration requested from the copied XCI"
grep '^SEG37_DIV_XCI' "$work_dir/generate-ips.log"
echo "# Divider configuration embedded in the newly generated simulation model"
grep -E 'C_LATENCY =>|DIVCLK_SEL =>' "$divider_model" | head -2
if sed -n '/ENTITY div_gen_uradix2 IS/,/END div_gen_uradix2;/p' "$divider_model" | grep -q 'tready'; then
  divider_has_tready=1
else
  divider_has_tready=0
fi
echo "SEG37_DIV_MODEL has_tready=$divider_has_tready model=$divider_model"
if ! grep -q "^SEG37_DIV_XCI clocks_per_division=$expected_div_cpd latency=$expected_div_latency " \
  "$work_dir/generate-ips.log"; then
  echo "Unexpected divider XCI configuration; expected CPD=$expected_div_cpd latency=$expected_div_latency" >&2
  exit 1
fi
if ! grep -q "C_LATENCY => $expected_div_latency" "$divider_model" ||
  ! grep -q "DIVCLK_SEL => $expected_div_cpd" "$divider_model" ||
  [ "$divider_has_tready" != "$expected_div_tready" ]; then
  echo "Generated divider model does not match the expected CPD/latency/TREADY configuration" >&2
  exit 1
fi

find "$ip_project" -type f \( -name '*.mif' -o -name '*.mem' \) -exec cp -f -- '{}' "$work_dir" \;
cp "$test_files/tb_seg37.sv" "$test_files/runall.tcl" "$work_dir/"

cd "$work_dir"
xvhdl --work xil_defaultlib "${ip_vhdl[@]}" >xvhdl.log 2>&1
xvlog --sv --work xil_defaultlib \
  "${ip_verilog[@]}" "${rtl_sources[@]}" tb_seg37.sv \
  "$repo_root/jyd-vivado-proj/digital_twin.srcs/sources_1/new/counter.sv" \
  "$repo_root/jyd-vivado-proj/digital_twin.srcs/sources_1/new/jyd_uart_subsystem.sv" \
  "$vivado_root/data/verilog/src/glbl.v" >xvlog.log 2>&1
xelab -a --debug typical --snapshot tb_seg37 \
  xil_defaultlib.tb_seg37 xil_defaultlib.glbl \
  -L xil_defaultlib -L blk_mem_gen_v8_4_9 -L dist_mem_gen_v8_0_15 \
  -L mult_gen_v12_0_22 -L div_gen_v5_1_23 -L xpm \
  -L axi_clock_converter_v2_1_32 -L axi_uartlite_v2_0_37 \
  -L axi_infrastructure_v1_1_0 -L axi_lite_ipif_v3_0_4 \
  -L lib_srl_fifo_v1_0_4 -L lib_cdc_v1_0_3 \
  -L unisims_ver -L unimacro_ver -L secureip >xelab.log 2>&1

set +e
LD_LIBRARY_PATH="$vivado_root/lib/lnx64.o${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  timeout --foreground "${timeout_seconds}s" \
  "$work_dir/xsim.dir/tb_seg37/axsim" -tclbatch "$work_dir/runall.tcl" \
  >"$work_dir/xsim.log" 2>&1
xsim_status=$?
set -e

grep -E 'SEG37_(START|UPDATE|PASS|FAIL)' "$work_dir/xsim.log" || true
if grep -q 'SEG37_PASS' "$work_dir/xsim.log"; then
  echo "SEG37_RESULT=PASS"
  exit 0
fi
if [ "$xsim_status" -eq 124 ]; then
  echo "SEG37_RESULT=FAIL reason=host_timeout seconds=$timeout_seconds log=$work_dir/xsim.log" >&2
else
  echo "SEG37_RESULT=FAIL reason=xsim_exit status=$xsim_status log=$work_dir/xsim.log" >&2
fi
exit 1
