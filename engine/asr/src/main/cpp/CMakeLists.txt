cmake_minimum_required(VERSION 3.22.1)

project("whisper_jni")

# Note: whisper.cpp source or prebuilt library will be linked here by Yarik's build setup
add_library(whisper_jni SHARED
    whisper_jni.cpp
)

find_library(log-lib log)

target_include_directories(whisper_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper.cpp
)

target_link_libraries(whisper_jni
    ${log-lib}
)

