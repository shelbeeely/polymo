/*
 * PolyMO Lite (Xteink X4) — UI seam, Phase 2 placeholder.
 *
 * pet_ble.c (reused unchanged from pet-esp32/main) calls these on every
 * remote update — mood changes, text, listening state, link/status, face-set
 * selection — the same seam pet-esp32.c fills on the S3 board with LVGL.
 * This board's real answer is a FreeInkUI screen composed from the same
 * geometry tables pet_faces.h already generates (design-system/faces/*.json
 * — see CLAUDE.md: those tables are vector geometry, not baked bitmaps, so
 * the S3's drawing logic is portable in SHAPE even though FreeInkUI's API
 * differs from LVGL's) — NOT WRITTEN YET. This file exists so the project
 * links and boots to something observable (log lines, one static screen)
 * while that port happens, the same way pet-esp32.c's own "MILESTONE 1 —
 * display-only bring-up" was a deliberate, honestly-labelled reduced first
 * pass rather than a fake one.
 *
 * pet_activity()/pet_is_asleep() are real, not placeholders — idle tracking
 * doesn't depend on FreeInkUI, so there is no reason to stub it.
 */
#include <cstring>

#include "esp_log.h"
#include "esp_timer.h"
#include "pet.h"

static const char *TAG = "pet_ui_x4";

static char s_face_set_id[24] = "default";
static volatile int64_t s_last_activity_us;
static const int64_t kIdleTimeoutUs = 5LL * 60 * 1000000; /* 5 min, matches nothing in particular yet — tune once there's a screen to watch go dark */

extern "C" void pet_set_text(const char *s) {
    ESP_LOGI(TAG, "[text] %s", s ? s : "(null)");
}

extern "C" void pet_set_mood(uint8_t mood) {
    ESP_LOGI(TAG, "[mood] %u", (unsigned)mood);
}

extern "C" esp_err_t pet_display_brightness(int percent) {
    (void)percent;
    /* BoardConfig::XTEINK_X4 carries NO_FRONTLIGHT - there is no backlight to
     * dim on a reflective e-paper panel. */
    return ESP_ERR_NOT_SUPPORTED;
}

extern "C" void pet_set_listening_ui(bool on) {
    ESP_LOGI(TAG, "[listening] %d", (int)on);
}

extern "C" void pet_chrome_wake(void) {
    pet_activity();
}

extern "C" void pet_set_link(bool connected) {
    ESP_LOGI(TAG, "[link] %d", (int)connected);
}

extern "C" void pet_set_status(uint8_t status) {
    ESP_LOGI(TAG, "[status] %u", (unsigned)status);
}

extern "C" const char *pet_face_set_id(void) {
    return s_face_set_id;
}

extern "C" bool pet_face_set_select(const char *id) {
    if (!id || !*id) {
        return false;
    }
    /* Real answer needs the same design-system/faces/*.json-generated table
     * pet-esp32.c indexes - not duplicated here yet. Accepts "default" only,
     * so a phone that never offers anything else (the common case today)
     * behaves correctly rather than silently lying about what it switched to. */
    if (strcmp(id, "default") != 0) {
        return false;
    }
    strncpy(s_face_set_id, id, sizeof(s_face_set_id) - 1);
    return true;
}

extern "C" uint8_t pet_face_set_count(void) {
    return 1;
}

extern "C" void pet_activity(void) {
    s_last_activity_us = esp_timer_get_time();
}

extern "C" bool pet_is_asleep(void) {
    return (esp_timer_get_time() - s_last_activity_us) > kIdleTimeoutUs;
}
