cmake_minimum_required(VERSION 3.22.1)
project("sherpa_onnx_jni")

set(CMAKE_CXX_STANDARD 17)

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

find_library(log-lib log)

add_library(sherpa_onnx_jni SHARED
    sherpa_onnx_jni.cpp
)

target_link_libraries(sherpa_onnx_jni PRIVATE
    sherpa-onnx-c-api
    onnxruntime
    ${log-lib}
)

