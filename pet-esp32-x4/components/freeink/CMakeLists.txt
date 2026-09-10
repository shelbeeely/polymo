# Vendored FreeInk SDK (https://github.com/Free-Ink/freeink-sdk, MIT — see
# licences/MIT-FreeInk.txt at the repo root). Written for the Arduino
# framework (PlatformIO's pioarduino fork), not native ESP-IDF, hence the
# `arduino-esp32` managed-component dependency below — this is the standard
# "Arduino as an ESP-IDF component" bridge, not an Arduino sketch replacing
# this project.
#
# WHY EVERYTHING GLOBS IN WITH NO PER-FILE EXCLUDES: every driver/manager this
# board doesn't have compiles down to a stub. Each file behind a board/vendor
# dependency we haven't vendored (LovyanGFX, M5GFX/M5Unified, wolfSSL,
# NimBLE-Arduino, USB Mass Storage's USBMSC) guards that #include behind its
# OWN capability macro (FREEINK_DRIVER_LGFX_EPD, FREEINK_DRIVER_M5_OFFICIAL,
# FREEINK_NET_WOLFSSL, FREEINK_CAP_BLE_HID_HOST, FREEINK_CAP_USB_MSC) and
# provides stub bodies in the #else branch — confirmed by reading each file,
# not assumed. Leaving those macros at their default (0, since -DFREEINK_
# DEVICE_X4=1 is the only device flag set below) is what "wire in all of the
# SDK" means here: every module is compiled and linked, and BoardConfig's
# XTEINK_X4 profile (NO_TOUCH/NO_FRONTLIGHT/NO_AUDIO/NO_LEDS/NO_MIC/
# NO_SENSORS/NO_GAUGE) is what makes the hardware-absent managers report
# false from their own hasX() rather than crash — that gating is the SDK's,
# not ours.
#
# FreeInkBook and ContentProtection (libs/book/) are deliberately NOT
# vendored at all — a real product feature (EPUB reading), not a hardware
# driver, and out of scope until/unless the pet actually reads books. Two
# FreeInkUI headers reference book:: types (FreeInkUIBookFont.h, and
# book-card.h transitively) but neither has a corresponding .cpp in FreeInkUI
# itself, so they simply must never be #included from our own code — do not
# add a `#include "FreeInkUIBookFont.h"` anywhere without vendoring
# libs/book first.
#
# Pruned entirely rather than vendored: hardware/BoardPaperS3,
# hardware/BoardT5S3 (board bring-up for M5Stack PaperS3 / LilyGo T5S3 —
# different products we don't own, and BoardPaperS3 additionally needs
# LovyanGFX). BoardConfig.h itself still carries every device's profile data
# in one header; only the OTHER boards' bring-up *code* was pruned.

file(GLOB_RECURSE freeink_srcs CONFIGURE_DEPENDS "libs/*/*/src/*.cpp")
file(GLOB freeink_include_dirs CONFIGURE_DEPENDS "libs/*/*/include")

idf_component_register(
    SRCS ${freeink_srcs}
    INCLUDE_DIRS ${freeink_include_dirs}
    REQUIRES arduino-esp32 SdFat esp_partition app_update spi_flash
)

# Matches libs' own reference config (platformio.sample.ini [base] +
# [env:xteink_x4]) as closely as an IDF component can:
#   -DFREEINK_DEVICE_X4=1            the only device profile linked
#   -DEINK_DISPLAY_SINGLE_BUFFER_MODE=1   no PSRAM here for a second buffer
#   -DARDUINO_USB_MODE=1 / -DARDUINO_USB_CDC_ON_BOOT=1   C3's native USB-Serial-JTAG
# NOT carried over: board_build.flash_mode/flash_size (that's sdkconfig.defaults'
# job here, not a compile define).
target_compile_definitions(${COMPONENT_LIB} PUBLIC
    FREEINK_DEVICE_X4=1
    EINK_DISPLAY_SINGLE_BUFFER_MODE=1
    ARDUINO_USB_MODE=1
    ARDUINO_USB_CDC_ON_BOOT=1
)

# platformio.sample.ini builds this SDK as `-std=gnu++2a` (C++20 + GNU
# extensions); match it so the same source compiles the same way.
target_compile_options(${COMPONENT_LIB} PUBLIC $<$<COMPILE_LANGUAGE:CXX>:-std=gnu++2a>)
