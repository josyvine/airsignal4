cmake_minimum_required(VERSION 3.22.1)

project("airsignal_audio")

# Add the native GGWave shared library
add_library(
    ggwave-native
    SHARED
    ggwave.cpp
    ggwave-jni.cpp
)

# Find Android logging library
find_library(
    log-lib
    log
)

# Link libraries
target_link_libraries(
    ggwave-native
    ${log-lib}
)