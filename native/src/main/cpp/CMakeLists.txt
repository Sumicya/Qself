# Qself native hook engine (Dobby).
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.28)
project(qself_native)

find_library(ANDROID_LIBS log)

macro(SET_OPTION option value)
    set(${option} ${value} CACHE INTERNAL "" FORCE)
endmacro()

enable_language(CXX C)

# Dobby is linked as a static library into a shared one; Android requires all
# code to be position independent, and Dobby's own CMakeLists does not ask for
# it. Same for 16KB-page devices.
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

# --- Qself JNI surface -------------------------------------------------------
add_library(qself_hook SHARED
        jni/qself_hook.cpp
)

set_target_properties(qself_hook PROPERTIES
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
)

# Dobby publishes its include directory on the target; being explicit keeps
# the header lookup working if the upstream CMake changes.
target_include_directories(qself_hook PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/Dobby
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/Dobby/include
)

target_link_libraries(qself_hook PRIVATE
        dobby
        ${ANDROID_LIBS}
)

# keep the .so relocatable on 16KB-page devices
target_link_options(qself_hook PRIVATE -Wl,-z,max-page-size=16384)
