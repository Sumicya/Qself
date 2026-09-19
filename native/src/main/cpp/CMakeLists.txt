# Qself native hook engine: Dobby (inline hooks) + LSPlant (ART Java hooks).
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.28)
project(qself_native)

find_library(ANDROID_LIBS log)

macro(SET_OPTION option value)
    set(${option} ${value} CACHE INTERNAL "" FORCE)
endmacro()

enable_language(CXX C)

# Everything is linked as static archives into one shared library; Android
# requires all code to be position independent, and neither upstream project
# asks for it. Same for 16KB-page devices.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC")

# --- Dobby -------------------------------------------------------------------
SET_OPTION(DOBBY_GENERATE_SHARED OFF)
# Do not use Dobby's symbol resolver: link it once and it crashes everywhere.
SET_OPTION(Plugin.SymbolResolver OFF)
# Note: Dobby's import-table replacement (DobbyImportTableReplace) is
# Darwin-only upstream — on Android the symbol is declared but never
# implemented, so the build must not reference it.
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/Dobby dobby_build)

# Dobby needs -fomit-frame-pointer on ARM32 (see upstream/previous setups)
if ("${ANDROID_ABI}" STREQUAL "armeabi-v7a")
    target_compile_options(dobby PRIVATE -fomit-frame-pointer)
endif ()

# --- LSPlant -----------------------------------------------------------------
# ART-level Java method hooking. Its CMakeLists is written to be included
# (it calls project() and sets C++23 + CXX modules for its own targets), and
# the static target is what gets linked in.
SET_OPTION(LSPLANT_BUILD_SHARED OFF)
add_subdirectory(
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/LSPlant/lsplant/src/main/jni
        lsplant_build
)

# --- Qself JNI surface -------------------------------------------------------
add_library(qself_hook SHARED
        jni/qself_hook.cpp
        art/art_symbols.cpp
        art/lsplant_bridge.cpp
)

set_target_properties(qself_hook PROPERTIES
        # C++20 is enough for our own code; LSPlant's targets carry C++23.
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED ON
)

target_include_directories(qself_hook PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        # Being explicit keeps header lookup working if upstream CMake changes.
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/Dobby
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/Dobby/include
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/LSPlant/lsplant/src/main/jni/include
)

target_link_libraries(qself_hook PRIVATE
        dobby
        lsplant_static
        ${ANDROID_LIBS}
        dl
)

# keep the .so relocatable on 16KB-page devices
target_link_options(qself_hook PRIVATE -Wl,-z,max-page-size=16384)
