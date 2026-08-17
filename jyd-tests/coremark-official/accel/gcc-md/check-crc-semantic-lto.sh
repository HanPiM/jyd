#!/usr/bin/env bash
set -euo pipefail

cc=$(realpath "${1:?usage: check-crc-semantic-lto.sh <patched-gcc>}")
case "$cc" in
  *-gcc) ;;
  *) echo "expected a GCC driver path, got $cc" >&2; exit 2 ;;
esac
tool_prefix=${cc%gcc}
objdump=${OBJDUMP:-${tool_prefix}objdump}
objcopy=${OBJCOPY:-${tool_prefix}objcopy}
test -x "$cc" || { echo "missing GCC driver: $cc" >&2; exit 2; }
test -x "$objdump" || { echo "missing objdump: $objdump" >&2; exit 2; }
test -x "$objcopy" || { echo "missing objcopy: $objcopy" >&2; exit 2; }

scratch=$(mktemp -d "${TMPDIR:-/srv/data/jyd/tmp}/crc-semantic-lto.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM
cat > "$scratch/link.ld" <<'EOF'
SECTIONS
{
  . = 0x80000000;
  .text : { *(.text*) }
  .rodata : { *(.rodata*) }
  .data : { *(.data*) }
  .bss : { *(.bss*) *(COMMON) }
}
EOF

common_flags=(
  -O3 -Os -flto -fno-pic -fno-builtin -ffreestanding
  -march=rv32im_zicsr -mabi=ilp32 -mxcrcu8
  -ffunction-sections -fdata-sections -nostdlib
  -Wl,-T,"$scratch/link.ld" -Wl,-e,_start -Wl,--gc-sections
)

count_crc() {
  local elf=$1
  "$objdump" -d "$elf" | python3 -c '
import sys
count = 0
for line in sys.stdin:
    fields = line.split()
    if len(fields) < 2 or len(fields[1]) != 8:
        continue
    try:
        word = int(fields[1], 16)
    except ValueError:
        continue
    if word & 0xfe00707f == 0x0000000b:
        count += 1
print(count)
'
}

build_fixture() {
  local name=$1
  local function_name=$2
  local loop_bound=$3
  local polynomial=$4
  local side_decl=$5
  local side_stmt=$6
  local semantic=$7
  local dir="$scratch/$name"
  mkdir -p "$dir"
  cat > "$dir/impl.c" <<EOF
typedef unsigned char u8;
typedef unsigned short u16;

${side_decl}

u16
$function_name (u8 data, u16 crc)
{
  u8 i = 0, x16 = 0, carry = 0;
  for (i = 0; i < $loop_bound; i++)
    {
      x16 = (u8)((data & 1) ^ ((u8)crc & 1));
      data >>= 1;
      $side_stmt
      if (x16 == 1)
        {
          crc ^= $polynomial;
          carry = 1;
        }
      else
        carry = 0;
      crc >>= 1;
      if (carry)
        crc |= 0x8000;
      else
        crc &= 0x7fff;
    }
  return crc;
}
EOF
  cat > "$dir/start.c" <<EOF
typedef unsigned char u8;
typedef unsigned short u16;
extern u16 $function_name (u8, u16);
volatile u8 crc_data_source = 0x5a;
volatile u16 crc_state_source = 0x1234;
volatile u16 crc_sink;
void _start (void)
{
  crc_sink = $function_name (crc_data_source, crc_state_source);
  for (;;) {}
}
EOF
  local extra=()
  if [[ "$semantic" == 1 ]]; then
    extra+=( -fcrc-semantic-lto )
  fi
  "$cc" "${common_flags[@]}" "${extra[@]}" "$dir/impl.c" "$dir/start.c" -o "$dir/test.elf"
  count_crc "$dir/test.elf"
}

positive=$(build_fixture positive byte_kernel 8 0x4002 '' '' 1)
[[ "$positive" == 1 ]] || { echo "positive CRC fixture emitted $positive sites" >&2; exit 1; }

renamed=$(build_fixture renamed renamed_byte_kernel 8 0x4002 '' '' 1)
[[ "$renamed" == "$positive" ]] || { echo "renamed CRC fixture count differs" >&2; exit 1; }
"$objcopy" -O binary --only-section=.text "$scratch/positive/test.elf" "$scratch/positive.text"
"$objcopy" -O binary --only-section=.text "$scratch/renamed/test.elf" "$scratch/renamed.text"
cmp -s "$scratch/positive.text" "$scratch/renamed.text" \
  || { echo "renamed CRC fixture machine code differs" >&2; exit 1; }

option_off=$(build_fixture option-off byte_kernel 8 0x4002 '' '' 0)
[[ "$option_off" -ge 1 ]] || { echo "option-off direct CRC fixture emitted no site" >&2; exit 1; }

wrong_trip=$(build_fixture wrong-trip byte_kernel 9 0x4002 '' '' 1)
[[ "$wrong_trip" == 0 ]] || { echo "wrong-trip CRC fixture emitted $wrong_trip sites" >&2; exit 1; }

wrong_poly=$(build_fixture wrong-poly byte_kernel 8 0x1234 '' '' 1)
[[ "$wrong_poly" == 0 ]] || { echo "wrong-polynomial CRC fixture emitted $wrong_poly sites" >&2; exit 1; }

volatile_decl='volatile unsigned char crc_side_effect;'
volatile_sites=$(build_fixture volatile byte_kernel 8 0x4002 "$volatile_decl" 'crc_side_effect ^= data;' 1)
[[ "$volatile_sites" == 0 ]] || { echo "volatile CRC fixture emitted $volatile_sites sites" >&2; exit 1; }

call_decl='volatile unsigned char call_sink; __attribute__((noinline)) void crc_side_effect_call (void) { call_sink++; }'
call_sites=$(build_fixture call byte_kernel 8 0x4002 "$call_decl" 'crc_side_effect_call ();' 1)
[[ "$call_sites" == 0 ]] || { echo "call CRC fixture emitted $call_sites sites" >&2; exit 1; }

printf 'CRC semantic-LTO fixture audit: PASS\n'
printf 'positive=%s renamed=%s option-off=%s near-miss=0\n' \
  "$positive" "$renamed" "$option_off"
