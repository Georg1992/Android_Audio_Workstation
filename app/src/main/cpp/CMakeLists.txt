
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)
project("audioworkstation")

# Create and name the library
add_library(audioworkstation SHARED audioworkstation.cpp)
add_library(native-lib SHARED native-lib.cpp)

# Find system libraries
find_library(log-lib log)
find_library(OpenSLES-lib OpenSLES)

# Link libraries to your target
target_link_libraries(audioworkstation ${log-lib} ${OpenSLES-lib})
