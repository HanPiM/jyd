#ifndef AM_CAMERA_H
#define AM_CAMERA_H

#include <stdint.h>

#define CAMERA_STATUS_CFG_DONE      (1u << 0)
#define CAMERA_STATUS_FRAME_VALID   (1u << 1)
#define CAMERA_STATUS_VIDEO_LOCKED  (1u << 2)
#define CAMERA_STATUS_CFG_ERROR     (1u << 3)
#define CAMERA_STATUS_HDMI_HPD      (1u << 4)
#define CAMERA_STATUS_SAMPLE_VALID  (1u << 5)

uint32_t camera_status(void);
uint32_t camera_frame_count(void);
uint8_t camera_sample_rgb332(void);
void camera_force_colorbar(int enable);

#endif
