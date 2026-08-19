cmake_minimum_required(VERSION 3.22.1)

project("airsignal_audio")

include(FetchContent)

# Automatically pulls the official ggwave C++ core during build
FetchContent_Declare(
    ggwave_src
    GIT_REPOSITORY https://github.com/ggerganov/ggwave.git
    GIT_TAG        master
)
FetchContent_MakeAvailable(ggwave_src)

# Build the native library combining ggwave C++ and your JNI bridge
add_library(
    ggwave-native
    SHARED
    ${ggwave_src_SOURCE_DIR}/src/ggwave.cpp
    ggwave-jni.cpp
)

target_include_directories(
    ggwave-native
    PRIVATE
    ${ggwave_src_SOURCE_DIR}/include
    ${ggwave_src_SOURCE_DIR}/include/ggwave
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

target_link_libraries(
    ggwave-native
    ${log-lib}
)