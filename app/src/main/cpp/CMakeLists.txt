cmake_minimum_required(VERSION 3.22.1)
project(call_mp3_bridge LANGUAGES CXX)

find_package(lame REQUIRED CONFIG)
add_library(mp3bridge SHARED mp3bridge.cpp)
find_library(android-log log)
target_link_libraries(mp3bridge PRIVATE lame::libmp3lame.so ${android-log})
target_compile_options(mp3bridge PRIVATE -Wall -Wextra -Werror)
