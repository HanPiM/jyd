#include <am.h>
#include <camera.h>
#include <klib.h>
#include <klib-macros.h>
#include <my_putnum.h>
#include <stdint.h>

extern uint32_t CAMERA_CONTROL_REG[];

static void short_delay(void) {
  for (volatile uint32_t i = 0; i < 4096; ++i) {
    asm volatile("" ::: "memory");
  }
}

int main(const char *args) {
  uint32_t status;
  uint32_t frame_before;
  uint32_t frame_after;
  uint8_t sample;

  (void)args;
  status = camera_status();
  frame_before = camera_frame_count();
  sample = camera_sample_rgb332();

  putstr("[JYD CAM] status=0x");
  putnum_base16(status);
  putstr("\n[JYD CAM] frame=");
  putnum(frame_before);
  putstr("\n[JYD CAM] sample_rgb332=0x");
  putnum_base16(sample);
  putch('\n');

  short_delay();
  frame_after = camera_frame_count();
  camera_force_colorbar(1);
  short_delay();
  if (*(volatile uint32_t *)CAMERA_CONTROL_REG != 1u) {
    putstr("[JYD CAM] CONTROL readback FAIL\n");
    return 1;
  }
  camera_force_colorbar(0);

  if ((status & CAMERA_STATUS_CFG_DONE) == 0 ||
      (status & CAMERA_STATUS_SAMPLE_VALID) == 0 ||
      frame_after == frame_before ||
      *(volatile uint32_t *)CAMERA_CONTROL_REG != 0u) {
    putstr("[JYD CAM] MMIO test FAIL\n");
    return 1;
  }

  putstr("[JYD CAM] MMIO test PASS\n");
  return 0;
}
