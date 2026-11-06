cmake_minimum_required(VERSION 3.22.1)
project("sherpa_onnx_jni")

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../.." ABSOLUTE)
set(SHERPA_ANDROID_DIR "${REPO_ROOT}/external/sherpa-onnx-android")

find_library(log-lib log)

# Prebuilt sherpa-onnx-c-api library from external/sherpa-onnx-android
add_library(sherpa-onnx-c-api SHARED IMPORTED)
set_target_properties(sherpa-onnx-c-api PROPERTIES
    IMPORTED_LOCATION "${SHERPA_ANDROID_DIR}/jniLibs/${ANDROID_ABI}/libsherpa-onnx-c-api.so"
)

add_library(sherpa_onnx_jni SHARED
    sherpa_onnx_jni.cpp
)

target_include_directories(sherpa_onnx_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${SHERPA_ANDROID_DIR}/include
)

target_link_libraries(sherpa_onnx_jni PRIVATE
    sherpa-onnx-c-api
    ${log-lib}
)
