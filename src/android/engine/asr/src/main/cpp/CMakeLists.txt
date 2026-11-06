cmake_minimum_required(VERSION 3.22.1)
project("whisper_jni")

# Resolve repository root: src/android/engine/asr/src/main/cpp -> 7 levels up
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../.." ABSOLUTE)
set(WHISPER_DIR "${REPO_ROOT}/external/whisper.cpp")

find_library(log-lib log)

# Collect whisper.cpp and ggml sources from external submodule
file(GLOB WHISPER_SRCS
    "${WHISPER_DIR}/whisper.cpp"
    "${WHISPER_DIR}/ggml.c"
    "${WHISPER_DIR}/ggml-alloc.c"
    "${WHISPER_DIR}/ggml-backend.c"
    "${WHISPER_DIR}/ggml-quants.c"
)

add_library(whisper_jni SHARED
    whisper_jni.cpp
    ${WHISPER_SRCS}
)

target_include_directories(whisper_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${WHISPER_DIR}
)

target_link_libraries(whisper_jni PRIVATE
    ${log-lib}
)

