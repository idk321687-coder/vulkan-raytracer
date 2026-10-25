cmake_minimum_required(VERSION 3.16)
project(VulkanRayTracer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# ==== Find Vulkan ====
if(Vulkan_LIBRARY AND Vulkan_INCLUDE_DIR)
    # Cross-compilation: Vulkan paths provided manually
    add_library(Vulkan::Vulkan UNKNOWN IMPORTED)
    set_target_properties(Vulkan::Vulkan PROPERTIES
        IMPORTED_LOCATION "${Vulkan_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${Vulkan_INCLUDE_DIR}"
    )
    message(STATUS "Vulkan (manual): ${Vulkan_LIBRARY}")
else()
    find_package(Vulkan REQUIRED)
endif()

# ==== Find GLFW ====
find_package(glfw3 3.3 QUIET)
if(NOT TARGET glfw)
    # Cross-compilation fallback: use GLFW_LIBRARY and GLFW_INCLUDE_DIR
    if(GLFW_LIBRARY AND GLFW_INCLUDE_DIR)
        add_library(glfw STATIC IMPORTED)
        set_target_properties(glfw PROPERTIES
            IMPORTED_LOCATION "${GLFW_LIBRARY}"
            INTERFACE_INCLUDE_DIRECTORIES "${GLFW_INCLUDE_DIR}"
        )
        message(STATUS "GLFW (manual): ${GLFW_LIBRARY}")
    else()
        # Try finding in CMAKE_PREFIX_PATH
        find_library(GLFW_LIBRARY_FOUND
            NAMES glfw3 glfw
            HINTS ${CMAKE_PREFIX_PATH}
            PATH_SUFFIXES lib-mingw-w64 lib
        )
        find_path(GLFW_INCLUDE_FOUND
            NAMES GLFW/glfw3.h
            HINTS ${CMAKE_PREFIX_PATH}
            PATH_SUFFIXES include
        )
        if(GLFW_LIBRARY_FOUND AND GLFW_INCLUDE_FOUND)
            add_library(glfw STATIC IMPORTED)
            set_target_properties(glfw PROPERTIES
                IMPORTED_LOCATION "${GLFW_LIBRARY_FOUND}"
                INTERFACE_INCLUDE_DIRECTORIES "${GLFW_INCLUDE_FOUND}"
            )
            message(STATUS "GLFW (discovered): ${GLFW_LIBRARY_FOUND}")
        else()
            message(FATAL_ERROR "GLFW not found. Set GLFW_LIBRARY and GLFW_INCLUDE_DIR.")
        endif()
    endif()
endif()

# ==== Find shader compiler (host tools — never cross-compiled) ====
# When cross-compiling, glslc/glslangValidator must be a host binary
find_program(GLSLC glslc)
if(NOT GLSLC)
    find_program(GLSLC glslangValidator)
endif()

if(NOT GLSLC)
    message(FATAL_ERROR "Neither glslc nor glslangValidator found. Install glslang-tools.")
endif()

get_filename_component(GLSLC_NAME "${GLSLC}" NAME)
if(GLSLC_NAME MATCHES "glslang")
    set(GLSLC_IS_GLSLANG TRUE)
endif()

# ==== Compile shaders ====
set(SHADER_SRC_DIR ${CMAKE_SOURCE_DIR}/shaders)
set(SHADER_BIN_DIR ${CMAKE_BINARY_DIR}/shaders)
file(MAKE_DIRECTORY ${SHADER_BIN_DIR})

set(SHADER_SOURCES
    ${SHADER_SRC_DIR}/raytrace.comp
)

foreach(SHADER_SRC ${SHADER_SOURCES})
    get_filename_component(SHADER_NAME ${SHADER_SRC} NAME)
    set(SPIRV_OUTPUT ${SHADER_BIN_DIR}/${SHADER_NAME}.spv)

    if(GLSLC_IS_GLSLANG)
        add_custom_command(
            OUTPUT ${SPIRV_OUTPUT}
            COMMAND ${GLSLC} -V ${SHADER_SRC} -o ${SPIRV_OUTPUT}
            DEPENDS ${SHADER_SRC}
            COMMENT "Compiling ${SHADER_NAME} (glslangValidator)"
        )
    else()
        add_custom_command(
            OUTPUT ${SPIRV_OUTPUT}
            COMMAND ${GLSLC} ${SHADER_SRC} -o ${SPIRV_OUTPUT}
            DEPENDS ${SHADER_SRC}
            COMMENT "Compiling ${SHADER_NAME} (glslc)"
        )
    endif()

    list(APPEND SPIRV_OUTPUTS ${SPIRV_OUTPUT})
endforeach()

add_custom_target(shaders ALL DEPENDS ${SPIRV_OUTPUTS})

# ==== Main executable ====
add_executable(raytracer src/main.cpp)
target_link_libraries(raytracer PRIVATE Vulkan::Vulkan glfw)
add_dependencies(raytracer shaders)

# Windows needs gdi32 for GLFW static linking
if(WIN32)
    target_link_libraries(raytracer PRIVATE gdi32 user32 shell32)
endif()

# Copy compiled shaders next to the executable
add_custom_command(TARGET raytracer POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        ${SHADER_BIN_DIR}
        $<TARGET_FILE_DIR:raytracer>/shaders
    COMMENT "Copying SPIR-V shaders to output directory"
)
