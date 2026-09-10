/*
 * PolyMO Lite (Xteink X4) — elapsed-time clock, no external RTC chip.
 *
 * The S3 board's pet_rtc.c (pet-esp32/main/pet_rtc.c) reads a PCF85063 that
 * the AXP2101 keeps powered off the LiPo even with the pet switched off, so
 * elapsed time survives a full power-down. This board has neither chip —
 * FreeInk's own BoardConfig::XTEINK_X4 profile carries RtcType::None, and
 * that's confirmed against the real board, not a gap in the port — so there
 * is no clock domain that survives a battery pull. What DOES survive is the
 * SoC's own RTC timer domain across *deep sleep* (not a hard power-off): a
 * value in RTC_DATA_ATTR storage keeps counting through PowerManager's
 * deepSleepUntilPowerButton(), because deep sleep never powers down the RTC
 * peripheral domain the way pulling the battery would.
 *
 * So, matching the S3 file's own approach (rtc_set_baseline(): "nothing shows
 * a clock, and in phase 1 there is no phone to ask what time it is — so a
 * fresh or stopped oscillator is simply started at a fixed epoch and counted
 * from there. Only the difference between two readings matters"): track
 * (fake epoch) = (a fixed baseline, planted whenever the anchor is missing) +
 * (esp_timer ticks since that baseline was planted). esp_timer keeps counting
 * correctly across our own deep sleep, because RTC_DATA_ATTR storage and the
 * RTC timer both live in the domain PowerManager's deep sleep leaves powered.
 * A cold boot — power-on reset, not a deep-sleep wake — means that
 * continuity is not trustworthy (brownout, battery pull, first flash), so it
 * is this board's version of the PCF85063's oscillator-stop flag: replant
 * the baseline and report time as lost, same as the S3 file does.
 */
#include "esp_attr.h"
#include "esp_log.h"
#include "esp_sleep.h"
#include "esp_timer.h"
#include "pet.h"

static const char *TAG = "pet_rtc_x4";

/* Retained across deep sleep (PowerManager::deepSleepUntilPowerButton), lost
 * on any other reset — power-on, brownout, panic, battery pull. */
static RTC_DATA_ATTR int64_t s_anchor_epoch_sec;   /* last known real epoch */
static RTC_DATA_ATTR int64_t s_anchor_uptime_us;    /* esp_timer_get_time() then */
static RTC_DATA_ATTR bool s_have_anchor;

static bool s_time_was_lost;

/* 2026-01-01 00:00:00 UTC, the same fixed baseline pet_rtc.c's
 * rtc_set_baseline() plants — arbitrary; only differences between readings
 * matter, and keeping the same constant across both boards is not required
 * but costs nothing. */
#define X4_RTC_BASELINE_EPOCH_SEC 1767225600LL

bool pet_rtc_start(void) {
    const bool woke_from_sleep =
        (esp_sleep_get_wakeup_cause() != ESP_SLEEP_WAKEUP_UNDEFINED);

    /* A cold boot invalidates the anchor even if RTC_DATA_ATTR happened to
     * survive (e.g. a plain reset button, not deep sleep) — only a real
     * deep-sleep wake vouches for the elapsed-time math below. */
    if (!woke_from_sleep) {
        s_have_anchor = false;
    }

    s_time_was_lost = !s_have_anchor;
    if (s_time_was_lost) {
        ESP_LOGW(TAG, "no time anchor (cold boot) - starting from baseline");
        s_anchor_epoch_sec = X4_RTC_BASELINE_EPOCH_SEC;
        s_anchor_uptime_us = esp_timer_get_time();
        s_have_anchor = true;
    }

    int64_t now = 0;
    pet_rtc_now(&now);
    ESP_LOGI(TAG, "ready - now %lld, time_was_lost=%d", (long long)now, s_time_was_lost);
    return true;
}

bool pet_rtc_now(int64_t *out_epoch_sec) {
    if (out_epoch_sec == NULL || !s_have_anchor) {
        return false;
    }
    const int64_t elapsed_us = esp_timer_get_time() - s_anchor_uptime_us;
    *out_epoch_sec = s_anchor_epoch_sec + (elapsed_us / 1000000);
    return true;
}

bool pet_rtc_time_was_lost(void) {
    return s_time_was_lost;
}
