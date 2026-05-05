cmake_minimum_required(VERSION 3.22.1)
project("audioworkstation")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)

include(FetchContent)
set(FETCHCONTENT_UPDATES_DISCONNECTED OFF)

set(OBOE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(OBOE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(OBOE_ENABLE_ASSERTIONS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.10.0
)
FetchContent_MakeAvailable(oboe)

option(BUILD_RINGBUFFER_GTESTS "Build RingBuffer GoogleTest executable" OFF)

if(BUILD_RINGBUFFER_GTESTS)
    FetchContent_Declare(
        googletest
        GIT_REPOSITORY https://github.com/google/googletest.git
        GIT_TAG v1.14.0
    )
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)

    add_executable(ringbuffer_tests tests/RingBuffer_test.cpp)
    target_include_directories(ringbuffer_tests PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
    )
    target_compile_features(ringbuffer_tests PRIVATE cxx_std_17)
    target_link_libraries(ringbuffer_tests PRIVATE GTest::gtest_main)
endif()

add_library(audioworkstation SHARED
    engine/AudioEngine.cpp
    engine/JNI_Bridge.cpp
    engine/LocalWavSource.cpp
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
