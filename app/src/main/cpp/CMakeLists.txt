cmake_minimum_required(VERSION 3.22.1)
project("audioworkstation")

find_library(log-lib log)

include(FetchContent)
set(FETCHCONTENT_UPDATES_DISCONNECTED OFF)

set(OBOE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(OBOE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(OBOE_ENABLE_ASSERTIONS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG main
)
FetchContent_MakeAvailable(oboe)

add_library(audioworkstation SHARED
    engine/AudioEngine.cpp
    engine/JNI_Bridge.cpp
    engine/OboeOutput.cpp
)

target_include_directories(audioworkstation PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(audioworkstation
    oboe
    ${log-lib}
    android
)
