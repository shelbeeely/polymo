/*
 * PolyMO Lite (Xteink X4) — no accelerometer.
 *
 * FreeInk's BoardConfig::XTEINK_X4 profile carries NO_SENSORS (ImuType::None)
 * for the base C3 X4 — confirmed against the vendored SDK, not assumed from
 * a teardown. The S3 board's QMI8658-driven shake-to-play gesture
 * (pet_imu.c) has no hardware here to replace it; "play" needs a button
 * mapping instead (see pet_x4_main.cpp), a product decision, not a firmware
 * stub.
 */
#include "pet.h"

bool pet_imu_start(pet_imu_shake_cb_t on_shake) {
    (void)on_shake;
    return false;
}

void pet_imu_debug(int32_t *peak, int32_t *rest, int32_t *threshold,
                   int32_t *held_ms, int32_t *hold_target_ms) {
    if (peak) *peak = 0;
    if (rest) *rest = 0;
    if (threshold) *threshold = 0;
    if (held_ms) *held_ms = 0;
    if (hold_target_ms) *hold_target_ms = 0;
}
