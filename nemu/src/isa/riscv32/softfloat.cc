#include <IDLHlper.hpp>

extern "C" {
#include <softfloat.h>
}

#include <cassert>
#include <cstdint>

extern "C" bool riscv_fp_enabled();
extern "C" void riscv_fp_mark_dirty();
extern "C" word_t riscv_raise_illegal_instruction(word_t instruction,
                                                   word_t pc);

namespace {

thread_local word_t *current_fcsr;
thread_local word_t *current_pc;
thread_local word_t current_instruction;

uint32_t bits(Bits<32> value) { return static_cast<uint32_t>(value.value); }
float32_t f32(Bits<32> value) { return float32_t{bits(value)}; }

void begin_operation(RoundingMode mode) {
  softfloat_roundingMode = static_cast<uint_fast8_t>(mode);
  softfloat_exceptionFlags = 0;
}

Bits<32> finish_operation(float32_t result) {
  assert(current_fcsr);
  *current_fcsr |= softfloat_exceptionFlags & 0x1f;
  softfloat_exceptionFlags = 0;
  return Bits<32>(result.v);
}

template <typename Result> Result finish_conversion(Result result) {
  assert(current_fcsr);
  *current_fcsr |= softfloat_exceptionFlags & 0x1f;
  softfloat_exceptionFlags = 0;
  return result;
}

uint32_t exponent(uint32_t value) { return (value >> 23) & 0xff; }
uint32_t fraction(uint32_t value) { return value & 0x7fffff; }
bool sign(uint32_t value) { return value >> 31; }
bool is_inf(uint32_t value) {
  return exponent(value) == 0xff && fraction(value) == 0;
}
bool is_zero(uint32_t value) {
  return exponent(value) == 0 && fraction(value) == 0;
}
bool is_subnormal(uint32_t value) {
  return exponent(value) == 0 && fraction(value) != 0;
}
bool is_normal(uint32_t value) {
  uint32_t exp = exponent(value);
  return exp != 0 && exp != 0xff;
}

} // namespace

namespace idl {

FpContextScope::FpContextScope(word_t *fcsr, word_t *pc, word_t instruction) {
  assert(!current_fcsr);
  current_fcsr = fcsr;
  current_pc = pc;
  current_instruction = instruction;
}

FpContextScope::~FpContextScope() {
  current_fcsr = nullptr;
  current_pc = nullptr;
}

void check_f_ok(word_t) {
  if (!riscv_fp_enabled())
    throw IllegalInstruction();
}

RoundingMode rm_to_mode(uint32_t rm, word_t) {
  if (rm == 7) {
    assert(current_fcsr);
    rm = (*current_fcsr >> 5) & 0x7;
  }
  if (rm > static_cast<uint32_t>(RoundingMode::RMM))
    throw IllegalInstruction();
  return static_cast<RoundingMode>(rm);
}

word_t handle_illegal_instruction() {
  assert(current_pc);
  return riscv_raise_illegal_instruction(current_instruction, *current_pc);
}

void mark_f_state_dirty() { riscv_fp_mark_dirty(); }

void set_fp_flag(FpFlag flag) {
  assert(current_fcsr);
  *current_fcsr |= static_cast<uint32_t>(flag);
}

Bits<64> nan_box(uint32_t narrow_width, uint32_t width, Bits<32> value) {
  assert(narrow_width == 32 && width == 64);
  return Bits<64>(UINT64_C(0xffffffff00000000) | bits(value));
}

Bits<32> f32_add(Bits<32> a, Bits<32> b, RoundingMode mode) {
  begin_operation(mode);
  return finish_operation(::f32_add(f32(a), f32(b)));
}

Bits<32> f32_sub(Bits<32> a, Bits<32> b, RoundingMode mode) {
  begin_operation(mode);
  return finish_operation(::f32_sub(f32(a), f32(b)));
}

Bits<32> f32_mul(Bits<32> a, Bits<32> b, RoundingMode mode) {
  begin_operation(mode);
  return finish_operation(::f32_mul(f32(a), f32(b)));
}

Bits<32> f32_div(Bits<32> a, Bits<32> b, RoundingMode mode) {
  begin_operation(mode);
  return finish_operation(::f32_div(f32(a), f32(b)));
}

Bits<32> f32_sqrt(Bits<32> a, RoundingMode mode) {
  begin_operation(mode);
  return finish_operation(::f32_sqrt(f32(a)));
}

Bits<32> f32_muladd(Bits<32> a, Bits<32> b, Bits<32> c,
                    F32MulAddOp op, RoundingMode mode) {
  uint32_t a_bits = bits(a);
  uint32_t c_bits = bits(c);
  if (op == F32MulAddOp::Softfloat_mulAdd_subC)
    c_bits ^= UINT32_C(0x80000000);
  else if (op == F32MulAddOp::Softfloat_mulAdd_subProd)
    a_bits ^= UINT32_C(0x80000000);

  begin_operation(mode);
  return finish_operation(
      ::f32_mulAdd(float32_t{a_bits}, f32(b), float32_t{c_bits}));
}

int32_t f32_to_i32(Bits<32> a, RoundingMode mode) {
  begin_operation(mode);
  return finish_conversion(
      ::f32_to_i32(f32(a), static_cast<uint_fast8_t>(mode), true));
}

uint32_t f32_to_ui32(Bits<32> a, RoundingMode mode) {
  begin_operation(mode);
  return finish_conversion(
      ::f32_to_ui32(f32(a), static_cast<uint_fast8_t>(mode), true));
}

Bits<32> i32_to_f32(uint32_t a, RoundingMode mode) {
  begin_operation(mode);
  return finish_operation(::i32_to_f32(static_cast<int32_t>(a)));
}

Bits<32> ui32_to_f32(uint32_t a, RoundingMode mode) {
  begin_operation(mode);
  return finish_operation(::ui32_to_f32(a));
}

bool is_sp_neg_inf(Bits<32> value) {
  return sign(bits(value)) && is_inf(bits(value));
}
bool is_sp_neg_norm(Bits<32> value) {
  return sign(bits(value)) && is_normal(bits(value));
}
bool is_sp_neg_subnorm(Bits<32> value) {
  return sign(bits(value)) && is_subnormal(bits(value));
}
bool is_sp_neg_zero(Bits<32> value) {
  return sign(bits(value)) && is_zero(bits(value));
}
bool is_sp_pos_zero(Bits<32> value) {
  return !sign(bits(value)) && is_zero(bits(value));
}
bool is_sp_pos_subnorm(Bits<32> value) {
  return !sign(bits(value)) && is_subnormal(bits(value));
}
bool is_sp_pos_norm(Bits<32> value) {
  return !sign(bits(value)) && is_normal(bits(value));
}
bool is_sp_pos_inf(Bits<32> value) {
  return !sign(bits(value)) && is_inf(bits(value));
}
bool is_sp_nan(Bits<32> value) {
  return exponent(bits(value)) == 0xff && fraction(bits(value)) != 0;
}
bool is_sp_signaling_nan(Bits<32> value) {
  return is_sp_nan(value) && !(bits(value) & UINT32_C(0x00400000));
}
bool is_sp_quiet_nan(Bits<32> value) {
  return is_sp_nan(value) && (bits(value) & UINT32_C(0x00400000));
}

} // namespace idl
