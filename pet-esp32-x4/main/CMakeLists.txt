# PolyMO Lite — Xteink X4 (ESP32-C3).
#
# Reuses the hardware-independent core of pet-esp32/main UNCHANGED, by source
# path rather than a copy, so a simulation/protocol change made on the S3
# board's tree cannot silently drift out of sync here:
#   pet_sim.c    the simulation itself — pure C, no display/BLE/audio calls.
#   pet_ble.c    NimBLE GATT server — portable as-is (NimBLE runs on C3 too).
#   pet.h / pet_proto.h   the seam and wire format both boards share.
#
# NOT reused, because they are genuinely hardware-specific to the S3 board:
#   pet-esp32.c, pet_mic.c, pet_spk.c, pet_imu.c, pet_pwr.c, pet_rtc.c
# This board's own pet_*_x4.c/.cpp files below implement the same pet.h seam
# against real X4 hardware (or a documented stub where the hardware simply
# isn't there — see each file's own header comment).
set(PET_ESP32_S3_MAIN "${CMAKE_CURRENT_LIST_DIR}/../../pet-esp32/main")

idf_component_register(
    SRCS
        "pet_x4_main.cpp"
        "pet_rtc_x4.c"
        "pet_imu_x4.c"
        "pet_mic_spk_x4.c"
        "pet_pwr_x4.cpp"
        "pet_ui_x4_stub.cpp"
        "${PET_ESP32_S3_MAIN}/pet_sim.c"
        "${PET_ESP32_S3_MAIN}/pet_ble.c"
    INCLUDE_DIRS "." "${PET_ESP32_S3_MAIN}"
    REQUIRES bt nvs_flash freeink
)

# Reduced capability bitmap — this board has no mic, speaker, IMU, or touch.
# See pet_proto.h's PET_CAPABILITIES (now #ifndef-guarded so this override
# doesn't touch the S3 build) and pet_ble.c's own self-check that every
# claimed bit has a matching GATT characteristic in its table.
#
# KNOWN GAP, not hidden: pet_ble.c's GATT service table itself is not
# conditionally compiled per capability bit (true on the S3 board too — nothing
# there needed it before). Reusing pet_ble.c unchanged means this board's BLE
# server still exposes Mic/Speaker characteristics even though the bitmap
# below says PET_CAP_MIC/PET_CAP_SPEAKER are unset. A well-behaved phone
# client should read the caps bitmap and simply never call them (the
# X4-side pet_mic_spk_x4.c stubs behind them are harmless no-ops either way),
# but the characteristics being visible at all is real drift from "this board
# doesn't have that" until pet_ble.c's table gains its own #if PET_CAP_MIC-
# style guards — a further shared-file change, intentionally not done here.
target_compile_definitions(${COMPONENT_LIB} PRIVATE
    "PET_CAPABILITIES=(PET_CAP_TEXT | PET_CAP_MOOD | PET_CAP_EVENTS | \
PET_CAP_STATUS | PET_CAP_SIM | PET_CAP_SCREEN | PET_CAP_LIFE | \
PET_CAP_BATTERY | PET_CAP_CLOCK | PET_CAP_FACESET | PET_CAP_QUIET)"
)

target_compile_options(${COMPONENT_LIB} PRIVATE $<$<COMPILE_LANGUAGE:CXX>:-std=gnu++2a>)
