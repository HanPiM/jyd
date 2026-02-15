#pragma once

#include <cassert>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string_view>
#include <vector>

void init_mem_logger();

struct mem_region_traits {
  const uint32_t _Base;
  const uint32_t _End;
  std::string_view name;

  mem_region_traits(uint32_t base, uint32_t end, std::string_view name)
      : _Base(base), _End(end), name(name) {
    assert(end > base && "end should be greater than base");
  }

  constexpr bool contains(uint32_t addr) const {
    return addr >= _Base && addr < _End;
  }
  constexpr uint32_t base() const { return _Base; }

  void assert_in_range(uint32_t addr) const;

  virtual void fill(uint8_t val) = 0;
};

struct direct_mapped_mem : public mem_region_traits {
  const uint32_t _ActualSizeInBytes;

  using _MemContainerType = std::vector<uint32_t>;
  std::shared_ptr<_MemContainerType> mem_container;
  uint32_t *data;

  direct_mapped_mem(uint32_t base, uint32_t end, std::string_view name,
                    uint32_t actual_size = 0)
      : mem_region_traits(base, end, name),
        _ActualSizeInBytes(actual_size ? actual_size : (end - base)) {
    assert(_ActualSizeInBytes <= (end - base) &&
           "actual size should not exceed the address range");
    assert(_ActualSizeInBytes % 4 == 0 && "size should be multiple of 4");
    mem_container = std::make_shared<_MemContainerType>(_ActualSizeInBytes / 4);
    data = mem_container->data();
  }

  void assert_in_actual_data_range(uint32_t addr) const;

  inline void copy_from(void *src, size_t siz) { memcpy(data, src, siz); }
  inline void fill(uint8_t val) override {
    memset(data, val, _ActualSizeInBytes);
  }

  uint8_t *get_data_ptr_at(uint32_t addr) {
    assert_in_range(addr);
    assert_in_actual_data_range(addr);
    return (uint8_t *)&data[(addr - _Base) / 4] + (addr & 0x3);
  }

  bool read_word(uint32_t addr, uint32_t &value) {
    assert_in_range(addr);
    assert_in_actual_data_range(addr);
    value = data[(addr - _Base) / 4]; // word aligned
    return true;
    // TODO: ret false instead of assert
  }
  bool write_word(uint32_t addr, uint32_t value) {
    assert_in_range(addr);
    assert_in_actual_data_range(addr);
    data[(addr - _Base) / 4] = value;
    return true;
    // TODO: ret false instead of assert
  }

  void write_word(uint32_t addr, uint32_t value, uint8_t strb8) {
    assert_in_range(addr);
    assert_in_actual_data_range(addr);
    uint8_t shift = (addr & 0x3) * 8;
    uint32_t strb32 = 0;
    if (strb8 & 0x1)
      strb32 |= 0x000000ff;
    if (strb8 & 0x2)
      strb32 |= 0x0000ff00;
    if (strb8 & 0x4)
      strb32 |= 0x00ff0000;
    if (strb8 & 0x8)
      strb32 |= 0xff000000;
    uint32_t shMask = strb32 << shift;
    uint32_t shData = value << shift;

    uint32_t &ref = data[(addr - _Base) / 4];
    ref &= ~shMask;
    ref |= (shData & shMask);
  }
};

struct sdram_mem : public mem_region_traits {
  static constexpr size_t N_BLOCKS = 4;
  static constexpr size_t N_BANKS = 4;
  static constexpr size_t N_ROWS = 8192;
  static constexpr size_t N_COLS = 512;

  sdram_mem(uint32_t base, uint32_t end, std::string_view name)
      : mem_region_traits(base, end, name) {
    size_t required_cont = N_BANKS * N_ROWS * N_COLS * N_BLOCKS;
    assert(required_cont * sizeof(uint16_t) <= (end - base) &&
           "sdram size should not exceed the address range");
    mem_container = std::make_shared<_MemContainerType>(required_cont);
  }

  /*
    sdram_data[N_BANKS][N_ROWS][N_COLS][N_BLOCKS];

    4 dimensions bank, row, col, block
    are linearly mapped to a 1-d vector mem_container

    Note that this is not addr to data mapping
    just a way to organize the data in memory!!!
  */
  using _MemContainerType = std::vector<uint16_t>;
  std::shared_ptr<_MemContainerType> mem_container;
  uint16_t &data_at(size_t bank, size_t row, size_t col, size_t block);

  void fill(uint8_t val) override {
    std::fill(mem_container->begin(), mem_container->end(), (uint16_t)val);
  }

  struct u32_data_ptr {
    uint16_t *lowpart;
    uint16_t *highpart;
  };
  u32_data_ptr get_data_at(uint32_t addr);
  uint8_t *get_data_ptr_at(uint32_t addr);

  bool read_half(uint32_t block, uint32_t bank, uint32_t row, uint32_t col,
                 uint16_t &value) {
    value = data_at(bank, row, col, block);
    return true;
  }
  bool write_half(uint32_t block, uint32_t bank, uint32_t row, uint32_t col,
                  uint16_t value) {
    data_at(bank, row, col, block) = value;
    return true;
  }

  bool read_word(uint32_t addr, uint32_t &value) {
    u32_data_ptr ptrs = get_data_at(addr);
    value = ((uint32_t)(*ptrs.highpart) << 16) | (uint32_t)(*ptrs.lowpart);
    return true;
  }
  bool write_word(uint32_t addr, uint32_t value) {
    u32_data_ptr ptrs = get_data_at(addr);
    *ptrs.lowpart = (uint16_t)(value & 0xffff);
    *ptrs.highpart = (uint16_t)((value >> 16) & 0xffff);
    return true;
  }
};
