/*
 * PolyMO Lite (Xteink X4) — battery, the power button, and deep sleep.
 *
 * Replaces pet-esp32/main/pet_pwr.c's AXP2101 register reads: this board has
 * no PMIC at all. FreeInk's BatteryMonitor reads a plain ADC-divided LiPo
 * voltage instead (BoardConfig::XTEINK_X4 carries NO_GAUGE, confirmed
 * against the vendored SDK — no I2C fuel gauge here), and PowerManager owns
 * the deep-sleep GPIO-wakeup path this board actually wants: unlike the S3
 * board, deep sleep is not a "separate, riskier step" here — see
 * sdkconfig.defaults — because there is no PSRAM/LVGL/audio DMA to starve,
 * and the e-paper panel keeps showing its last frame with the rail off. What
 * is NOT yet resolved is whether deep sleep and a live BLE connection
 * coexist cleanly on this board (the S3's own light-sleep attempt broke BLE
 * connection timing off an imprecise RTC clock source — see that board's
 * sdkconfig.defaults) — that needs measuring on hardware, not assumed here
 * either way, so nothing in this file puts the radio to sleep on its own
 * initiative yet.
 *
 * InputManager (all six buttons + power) is owned here rather than in the
 * not-yet-written UI layer, because pet.h's own comment ties "the physical
 * buttons" to the pwr seam (it meant PWR+BOOT on the S3 board, which had no
 * touch — this board has no touch either, just more buttons). The UI/nav
 * layer reads the same instance via pet_x4_input().
 */
#include <BatteryMonitor.h>
#include <InputManager.h>
#include <PowerManager.h>

#include "esp_log.h"
#include "pet.h"
#include "pet_x4_input.h"

static const char *TAG = "pet_pwr_x4";

static BatteryMonitor s_battery;
static InputManager s_input;

InputManager &pet_x4_input(void) {
    return s_input;
}

extern "C" bool pet_pwr_start(void) {
    s_input.begin();
    // Async polling so a button press mid e-paper-refresh isn't lost — see
    // InputManager.h's own reasoning: a slow refresh blocks the main loop,
    // and the background task keeps sampling through it.
    s_input.beginAsync();

    if (!freeink::PowerManager::armPowerButtonWakeup()) {
        ESP_LOGW(TAG, "no power-button wake pin in this board profile");
    }

    const BatteryMonitor::Status st = s_battery.readStatus();
    ESP_LOGI(TAG, "ready - battery supported=%d", st.supported);
    return true;
}

extern "C" bool pet_pwr_battery(uint8_t *percent, uint16_t *millivolts, bool *charging) {
    const BatteryMonitor::Status st = s_battery.readStatus();
    if (!st.supported) {
        return false;
    }
    if (percent) *percent = st.percentageKnown ? (uint8_t)st.percentage : 0xFF;
    if (millivolts) *millivolts = st.millivoltsKnown ? st.millivolts : 0;
    if (charging) *charging = st.chargingKnown ? st.charging : false;
    return true;
}

extern "C" void pet_pwr_battery_probe(void) {
    const BatteryMonitor::Status st = s_battery.readStatus();
    ESP_LOGI(TAG,
             "battery probe - supported=%d pct=%u (known=%d) mV=%u (known=%d) "
             "charging=%d (known=%d) extPower=%d (known=%d)",
             st.supported, st.percentage, st.percentageKnown, st.millivolts,
             st.millivoltsKnown, st.charging, st.chargingKnown, st.externalPower,
             st.externalPowerKnown);
}

extern "C" void pet_pwr_dump_rails(void) {
    // Nothing to dump: BoardConfig::XTEINK_X4 has no PMIC and no gated power
    // rails (NO_TOUCH/NO_FRONTLIGHT/NO_AUDIO/NO_LEDS all carry
    // PIN_UNASSIGNED power-enable pins) — PowerManager::powerDownRailsForSleep()
    // is a documented no-op on this exact profile. Saying so plainly beats a
    // dump that would just print "unassigned" seven times.
    ESP_LOGI(TAG, "no PMIC / gated rails on this board profile - nothing to dump");
}
