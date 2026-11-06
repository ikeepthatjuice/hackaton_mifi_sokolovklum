cmake_minimum_required(VERSION 3.22.1)

project("sherpa_onnx_jni")

# Note: sherpa-onnx c-api and onnxruntime will be linked here by Yarik's build setup
add_library(sherpa_onnx_jni SHARED
    sherpa_onnx_jni.cpp
)

find_library(log-lib log)

target_include_directories(sherpa_onnx_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../sherpa-onnx
)

target_link_libraries(sherpa_onnx_jni
    ${log-lib}
)

