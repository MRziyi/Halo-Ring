cmake_minimum_required(VERSION 3.22.1)
project(halo_spake2)

# Prefab AAR providing prebuilt BoringSSL as a static archive.
find_package(boringssl REQUIRED CONFIG)

add_library(halo_spake2 SHARED spake2_jni.cpp)
target_link_libraries(halo_spake2 log boringssl::crypto_static)
target_compile_options(halo_spake2 PRIVATE -Wall -Werror -Os -fvisibility=hidden)
