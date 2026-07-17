cmake_minimum_required(VERSION 3.22.1)

project("bonsai-runtime" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "iBit currently supports arm64-v8a only")
endif()

set(GGML_SYSTEM_ARCH ARM)
set(GGML_CPU_KLEIDIAI ON)
set(GGML_OPENMP ON)

get_filename_component(LLAMA_SRC
    "${CMAKE_CURRENT_LIST_DIR}/../../../../.vendor/llama.cpp"
    ABSOLUTE)

# The Android NDK vendors Khronos SPIR-V headers but does not expose their CMake
# package. Point llama.cpp's Vulkan backend at a small package shim that reuses
# those exact NDK headers, keeping the build self-contained.
set("SPIRV-Headers_DIR"
    "${CMAKE_CURRENT_LIST_DIR}/cmake/SPIRV-Headers"
    CACHE PATH "Android NDK SPIR-V headers package" FORCE)

# CMake's Android FindVulkan module locates the loader but can miss the host
# glslc binary that ships with the NDK. Prism needs that compiler to embed its
# Vulkan kernels in libggml-vulkan.so.
set(BONSAI_NDK_GLSLC
    "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe")
if(NOT EXISTS "${BONSAI_NDK_GLSLC}")
    message(FATAL_ERROR "Android NDK glslc not found at ${BONSAI_NDK_GLSLC}")
endif()
set(Vulkan_GLSLC_EXECUTABLE
    "${BONSAI_NDK_GLSLC}"
    CACHE FILEPATH "Android NDK Vulkan shader compiler" FORCE)

# NDK 28 keeps the Vulkan C API in the sysroot but no longer bundles the
# header-only Vulkan-Hpp wrapper used by ggml-vulkan. The local wrapper is
# pinned to the same 1.3.275 header revision as this NDK.
set(BONSAI_VULKAN_HPP_INCLUDE_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../../.tools/Vulkan-Hpp"
    CACHE PATH "Vulkan-Hpp 1.3.275 include directory")
if(NOT EXISTS "${BONSAI_VULKAN_HPP_INCLUDE_DIR}/vulkan/vulkan.hpp")
    message(FATAL_ERROR
        "Vulkan-Hpp 1.3.275 not found at ${BONSAI_VULKAN_HPP_INCLUDE_DIR}")
endif()

# llama.cpp builds its Vulkan shader generator as a Windows host tool while
# cross-compiling the runtime for Android. Use an isolated LLVM-MinGW compiler
# for that host executable; Android runtime libraries still use the NDK.
set(BONSAI_LLVM_MINGW_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../../.tools/llvm-mingw"
    CACHE PATH "Local LLVM-MinGW toolchain used for Vulkan shader generation")
set(BONSAI_HOST_C_COMPILER
    "${BONSAI_LLVM_MINGW_DIR}/bin/x86_64-w64-mingw32-clang.exe")
set(BONSAI_HOST_CXX_COMPILER
    "${BONSAI_LLVM_MINGW_DIR}/bin/x86_64-w64-mingw32-clang++.exe")
if(NOT EXISTS "${BONSAI_HOST_C_COMPILER}" OR
   NOT EXISTS "${BONSAI_HOST_CXX_COMPILER}")
    message(FATAL_ERROR
        "Vulkan shader generation requires LLVM-MinGW at ${BONSAI_LLVM_MINGW_DIR}")
endif()

# Pass an explicit host toolchain so the nested CMake invocation also finds
# the same Ninja executable Gradle uses.
set(VULKAN_HOST_TOOLCHAIN
    "${CMAKE_CURRENT_BINARY_DIR}/vulkan-host-toolchain.cmake")
file(TO_CMAKE_PATH "${CMAKE_MAKE_PROGRAM}" VULKAN_HOST_NINJA)
configure_file(
    "${CMAKE_CURRENT_LIST_DIR}/cmake/vulkan-host-toolchain.cmake.in"
    "${VULKAN_HOST_TOOLCHAIN}"
    @ONLY)
set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
    "${VULKAN_HOST_TOOLCHAIN}"
    CACHE FILEPATH "Host toolchain for llama.cpp Vulkan shader generation" FORCE)

add_subdirectory("${LLAMA_SRC}" build-llama)

target_include_directories(ggml-vulkan SYSTEM PRIVATE
    "${BONSAI_VULKAN_HPP_INCLUDE_DIR}")

add_library(bonsai-runtime SHARED bonsai_runtime.cpp)

target_include_directories(bonsai-runtime PRIVATE
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src")

target_link_libraries(bonsai-runtime
    llama
    llama-common
    android
    log)
