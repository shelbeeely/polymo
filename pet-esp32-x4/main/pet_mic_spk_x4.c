/*
 * PolyMO Lite (Xteink X4) — no microphone, no speaker.
 *
 * FreeInk's BoardConfig::XTEINK_X4 profile carries NO_AUDIO and NO_MIC for
 * the base C3 X4 (confirmed against the vendored SDK) — no codec, no PDM
 * mic, no amp. The pet's voice conversation loop (pet_mic.c/pet_spk.c on the
 * S3 board) simply has no hardware path here; the BLE capability bitmap
 * (main/pet_x4_main.cpp) clears PET_CAP_MIC/PET_CAP_SPEAKER so the phone
 * app knows not to offer it, rather than these stubs silently pretending to
 * succeed.
 */
#include "pet.h"

void pet_mic_start(void) {}
void pet_spk_start(void) {}

void pet_spk_begin(void) {}
void pet_spk_push(const uint8_t *frame, uint8_t len) { (void)frame; (void)len; }
void pet_spk_end(void) {}
void pet_spk_abort(void) {}
bool pet_spk_is_playing(void) { return false; }
void pet_spk_beep(void) {}

bool pet_mic_is_listening(void) { return false; }
void pet_mic_set_listening(bool on) { (void)on; }
