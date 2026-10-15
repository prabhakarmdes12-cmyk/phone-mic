cmake_minimum_required(VERSION 3.22.1)

project("phonemic")

# Find Android libraries
find_library(log-lib log)
find_package(oboe REQUIRED CONFIG)

# Add native audio engine library
add_library(phonemic SHARED
    native-audio-engine.cpp
)

target_include_directories(phonemic PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(phonemic
    oboe::oboe
    ${log-lib}
)
