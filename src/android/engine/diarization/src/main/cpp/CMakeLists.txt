cmake_minimum_required(VERSION 3.22.1)
project("sherpa_onnx_jni")

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../.." ABSOLUTE)
set(SHERPA_DIR "${REPO_ROOT}/external/sherpa-onnx")

find_library(log-lib log)

add_library(sherpa_onnx_jni SHARED
    sherpa_onnx_jni.cpp
)

target_include_directories(sherpa_onnx_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${SHERPA_DIR}
    ${SHERPA_DIR}/sherpa-onnx/c-api
)

target_link_libraries(sherpa_onnx_jni PRIVATE
    ${log-lib}
)

