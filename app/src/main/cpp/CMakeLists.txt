cmake_minimum_required(VERSION 3.22.1)
project(dddplayer_native)

# libdovi 2.3.1 prebuilt by edde746/libdovi-builds (see app/build.gradle.kts downloadLibdovi)
add_library(dovi_static STATIC IMPORTED)
set_target_properties(dovi_static PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/libdovi/${ANDROID_ABI}/libdovi.a")

add_library(dovi_bridge SHARED dovi_bridge.cpp)
target_compile_definitions(dovi_bridge PRIVATE DOVI_REAL_LINKED=1)
target_include_directories(dovi_bridge PRIVATE ${CMAKE_SOURCE_DIR}/include)
target_link_libraries(dovi_bridge dovi_static log)
