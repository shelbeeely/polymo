cmake_minimum_required(VERSION 3.22.1)
project(digitalpet-native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ==============================================================================
# Compiler flags
# ==============================================================================
add_compile_options(-O3 -fvisibility=hidden)
add_link_options("-Wl,-z,max-page-size=16384")

# ==============================================================================
# Path definitions
#
# llama.cpp, whisper.cpp and Piper are gone from this build along with the
# GGUF/Whisper-.bin/Piper-.onnx pipelines they backed — the LLM and STT are
# Gemini Nano via AICore now (ML Kit GenAI, a Gradle dependency, not native
# source) and TTS is the platform android.speech.tts.TextToSpeech engine. See
# LlmManager.kt, SttService.kt and TtsService.kt.
#
# Opus stays: it is the pet-speaker/BLE audio codec, unrelated to which
# engine produces or consumes the PCM either side of it.
# ==============================================================================
set(DEPS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../deps")
set(OPUS_DIR "${DEPS_DIR}/opus")

# ==============================================================================
# Opus — build from source with fixed-point math
# ==============================================================================
set(OPUS_FIXED_POINT ON CACHE BOOL "" FORCE)
set(OPUS_DISABLE_FLOAT_API ON CACHE BOOL "" FORCE)
set(OPUS_DISABLE_INTRINSICS ON CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

add_subdirectory(${OPUS_DIR} ${CMAKE_CURRENT_BINARY_DIR}/opus)

# ==============================================================================
# JNI bridge — now just Opus
# ==============================================================================
add_library(digitalpet-native SHARED
    opus-jni.cpp
)

target_include_directories(digitalpet-native PRIVATE
    ${OPUS_DIR}/include
)

find_library(ANDROID_LOG_LIB log)

target_link_libraries(digitalpet-native PRIVATE
    opus
    ${ANDROID_LOG_LIB}
)
