cmake_minimum_required(VERSION 3.22.1)
project("karaokeengine")

find_package(oboe REQUIRED CONFIG)

add_library(karaokeengine SHARED
    native-lib.cpp
    KaraokeEngine.cpp
)

target_link_libraries(karaokeengine
    oboe::oboe
    log
    android
)
