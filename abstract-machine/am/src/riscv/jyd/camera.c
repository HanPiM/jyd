#include <camera.h>

extern uint32_t CAMERA_STATUS_REG[];
extern uint32_t CAMERA_FRAME_COUNT_REG[];
extern uint32_t CAMERA_SAMPLE_RGB_REG[];
extern uint32_t CAMERA_CONTROL_REG[];

uint32_t camera_status(void) {
  return *(volatile uint32_t *)CAMERA_STATUS_REG;
}

uint32_t camera_frame_count(void) {
  return *(volatile uint32_t *)CAMERA_FRAME_COUNT_REG;
}

uint8_t camera_sample_rgb332(void) {
  return (uint8_t)*(volatile uint32_t *)CAMERA_SAMPLE_RGB_REG;
}

void camera_force_colorbar(int enable) {
  *(volatile uint32_t *)CAMERA_CONTROL_REG = enable ? 1u : 0u;
}
