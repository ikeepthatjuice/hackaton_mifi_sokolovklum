cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni")

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "Do not build tests" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "Do not build examples" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build whisper as static library" FORCE)

# Submodule external/whisper.cpp (ebca09a, v1.7.1); 7 levels up from src/android/engine/asr/src/main/cpp = repo root.
set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../../external/whisper.cpp")
if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_DIR}: run git submodule update --init")
endif()

add_subdirectory("${WHISPER_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/whisper_build" EXCLUDE_FROM_ALL)

find_library(log-lib log)

add_library(whisper_jni SHARED
    whisper_jni.cpp
)

target_link_libraries(whisper_jni PRIVATE
    whisper
    ${log-lib}
)

# Sherpa-onnx for GigaAM Transducer ASR
set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../..")
set(SHERPA_ANDROID_DIR "${REPO_ROOT}/external/sherpa-onnx-android")

add_library(sherpa-onnx-c-api SHARED IMPORTED)
set_target_properties(sherpa-onnx-c-api PROPERTIES
    IMPORTED_LOCATION "${SHERPA_ANDROID_DIR}/jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so"
)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION "${SHERPA_ANDROID_DIR}/jniLibs/${ANDROID_ABI}/libonnxruntime.so"
)

include_directories(
    "${SHERPA_ANDROID_DIR}/include"
)

add_library(gigaam_jni SHARED
    gigaam_jni.cpp
)

target_link_libraries(gigaam_jni PRIVATE
    sherpa-onnx-c-api
    onnxruntime
    ${log-lib}
)
