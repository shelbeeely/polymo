/*
 * PolyMO Lite — Xteink X4 (ESP32-C3) entry point.
 *
 * Mirrors pet-esp32/main/pet-esp32.c's app_main ordering (NVS before
 * anything that touches it, RTC before the simulation that ages against
 * it, mic/speaker last and non-fatal) with the display/UI middle section
 * replaced: this board doesn't have LVGL, a UI queue/task, or touch, and its
 * FreeInkUI screen isn't written yet (see pet_ui_x4_stub.cpp) — what's here
 * is the "MILESTONE 1 — display-only bring-up" equivalent for this board:
 * enough to see the panel come up and the BLE link work, not the finished
 * face.
 */
#include <Arduino.h>
#include <BoardConfig.h>
#include <FreeInkDisplay.h>
#include <InputManager.h>
#include <SDCardManager.h>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "pet.h"
#include "pet_x4_input.h"

static const char *TAG = "pet_x4_main";

static FreeInkDisplay s_display;

extern "C" void app_main(void) {
    // Arduino-as-ESP-IDF-component: FreeInk is written against the Arduino
    // framework (Serial, pinMode/digitalRead, millis()), so this has to run
    // before anything below touches those — including the static
    // BatteryMonitor/InputManager instances in pet_pwr_x4.cpp, whose
    // constructors are safe to run before this (they only copy pin config)
    // but whose begin()/beginAsync() (called from pet_pwr_start(), below)
    // are not.
    initArduino();

    ESP_LOGI(TAG, "PolyMO Lite booting - board '%s', %ux%u %s",
             BoardConfig::ACTIVE.name, (unsigned)BoardConfig::ACTIVE.displayWidth,
             (unsigned)BoardConfig::ACTIVE.displayHeight,
             BoardConfig::ACTIVE.board == BoardConfig::Board::XteinkX4
                 ? "(confirmed XteinkX4 profile)"
                 : "(WRONG PROFILE - single-device build should not reach this)");

    /* NVS first - pet_sim.c's state and NimBLE's bonds both live in it,
     * same reasoning as the S3 board. */
    esp_err_t nv = nvs_flash_init();
    if (nv == ESP_ERR_NVS_NO_FREE_PAGES || nv == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ESP_ERROR_CHECK(nvs_flash_init());
    }

    /* --- board bring-up --------------------------------------------------
     * Display: full-refresh only for now - no FreeInkUI screen composed yet
     * (pet_ui_x4_stub.cpp logs mood/text instead of drawing them). SD: best
     * effort - a card that isn't inserted or doesn't mount should not stop
     * the pet from booting, same "never fatal" spirit as the S3 board's
     * touch/mic bring-up. */
    s_display.begin();
    ESP_LOGI(TAG, "display up - %ux%u", (unsigned)s_display.getDisplayWidth(),
             (unsigned)s_display.getDisplayHeight());

    if (SdMan.begin()) {
        ESP_LOGI(TAG, "SD card up - %llu MB total",
                 (unsigned long long)(SdMan.sdTotalBytes() / (1024 * 1024)));
    } else {
        ESP_LOGW(TAG, "no SD card (or mount failed) - continuing without it");
    }

    pet_ble_start();

    /* Non-fatal by construction, like the S3 board - these are stubs on
     * this board (no mic/speaker hardware), not a bring-up step that can
     * fail. */
    pet_mic_start();
    pet_spk_start();

    /* RTC before the simulation, same reasoning as the S3 board: without it
     * the scores hold rather than guess. */
    pet_rtc_start();
    pet_pwr_start();
    pet_pwr_dump_rails();
    pet_sim_start();
    pet_imu_start(NULL); /* no IMU on this board - see pet_imu_x4.c */

    ESP_LOGI(TAG, "ready");

    /* Placeholder main loop until the FreeInkUI screen exists: drain
     * InputManager's async queue so presses aren't silently dropped while
     * nothing yet reads them, and keep the idle clock honest. */
    for (;;) {
        uint8_t button;
        while (pet_x4_input().popPress(button)) {
            ESP_LOGI(TAG, "button %s pressed", InputManager::getButtonName(button));
            pet_activity();
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}
