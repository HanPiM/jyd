#include <camera.h>
#include <finsh.h>
#include <rtthread.h>
#include <stdint.h>
#include <string.h>

static void print_flag(const char *name, uint32_t status, uint32_t mask) {
  rt_kprintf("%s=%u\n", name, (status & mask) != 0);
}

static int camera(int argc, char **argv) {
  uint32_t status;

  (void)argc;
  (void)argv;
  status = camera_status();
  rt_kprintf("camera status=0x%08x\n", status);
  print_flag("cfg_done", status, CAMERA_STATUS_CFG_DONE);
  print_flag("frame_valid", status, CAMERA_STATUS_FRAME_VALID);
  print_flag("video_locked", status, CAMERA_STATUS_VIDEO_LOCKED);
  print_flag("cfg_error", status, CAMERA_STATUS_CFG_ERROR);
  print_flag("hdmi_hpd", status, CAMERA_STATUS_HDMI_HPD);
  print_flag("sample_valid", status, CAMERA_STATUS_SAMPLE_VALID);
  rt_kprintf("frame_count=%u\n", camera_frame_count());
  rt_kprintf("sample_rgb332=0x%02x\n", camera_sample_rgb332());
  return RT_EOK;
}
MSH_CMD_EXPORT(camera, show camera status and one live RGB332 sample.);

static int camera_bars(int argc, char **argv) {
  int enable;

  if (argc != 2 || (strcmp(argv[1], "on") != 0 && strcmp(argv[1], "off") != 0)) {
    rt_kprintf("usage: camera_bars on|off\n");
    return -RT_EINVAL;
  }

  enable = strcmp(argv[1], "on") == 0;
  camera_force_colorbar(enable);
  rt_kprintf("camera bars %s\n", enable ? "on" : "off");
  return RT_EOK;
}
MSH_CMD_EXPORT(camera_bars, force HDMI color bars on or off.);
