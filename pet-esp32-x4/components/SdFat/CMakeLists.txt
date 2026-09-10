# Vendored greiman/SdFat (https://github.com/greiman/SdFat, v2.3.1, MIT — see
# LICENSE.md in this directory and licences/MIT-SdFat.txt at the repo root).
#
# Pulled in because libs/hardware/SDCardManager/include/SDCardManager.h does
# an unconditional `#include <SdFat.h>` — FreeInk treats it the same way
# PlatformIO's dependency resolution does (platformio.sample.ini: "SdFat is
# pulled in automatically as a dependency of SDCardManager"), as an external
# library FreeInk itself doesn't vendor. Only src/ is pulled in here — not
# examples/, extras/, doc/, images/.
file(GLOB_RECURSE sdfat_srcs CONFIGURE_DEPENDS "src/*.cpp")

idf_component_register(
    SRCS ${sdfat_srcs}
    INCLUDE_DIRS "src"
    REQUIRES arduino-esp32
)

target_compile_options(${COMPONENT_LIB} PUBLIC $<$<COMPILE_LANGUAGE:CXX>:-std=gnu++2a>)
