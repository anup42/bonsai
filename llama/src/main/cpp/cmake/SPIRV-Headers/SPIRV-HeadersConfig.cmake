set(_SPIRV_HEADERS_INCLUDE_DIR
    "${CMAKE_ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")

if(NOT EXISTS "${_SPIRV_HEADERS_INCLUDE_DIR}/spirv/unified1/spirv.hpp")
    set(SPIRV-Headers_FOUND FALSE)
    message(FATAL_ERROR "The Android NDK SPIR-V headers were not found")
endif()

if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${_SPIRV_HEADERS_INCLUDE_DIR}")
endif()

include_directories(SYSTEM "${_SPIRV_HEADERS_INCLUDE_DIR}")
set(SPIRV-Headers_FOUND TRUE)
