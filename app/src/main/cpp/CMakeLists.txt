
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)
project("audioworkstation")

# Create and name the library
add_library(audioworkstation SHARED
	# existing sources
	audioworkstation.cpp
	native-lib.cpp
	# engine sources
	engine/AudioEngine.cpp
	engine/JNI_Bridge.cpp
)

# Find system libraries
find_library(log-lib log)

# Oboe - force cache refresh
include(FetchContent)
set(FETCHCONTENT_UPDATES_DISCONNECTED OFF)
FetchContent_Declare(
  oboe
  GIT_REPOSITORY https://github.com/google/oboe.git
  GIT_TAG main
  UPDATE_COMMAND ""
)
FetchContent_MakeAvailable(oboe)

# Link libraries to your target
target_include_directories(audioworkstation PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(audioworkstation oboe::oboe ${log-lib})
