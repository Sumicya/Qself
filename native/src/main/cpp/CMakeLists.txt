# Qself native hook engine.
# SPDX-License-Identifier: GPL-3.0-or-later

cmake_minimum_required(VERSION 3.28)
project(qself_native)

find_library(ANDROID_LIBS log)

macro(SET_OPTION option value)
    set(${option} ${value} CACHE INTERNAL "" FORCE)
endmacro()

enable_language(CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- Dobby (PLT / inline hooks) --------------------------------------------
SET_OPTION(DOBBY_GENERATE_SHARED OFF)
# Do not use Dobby symbol resolver. You link it once and it crashes everywhere.
SET_OPTION(Plugin.SymbolResolver OFF)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/Dobby dobby_build)

# --- LSPlant (ART inline hook engine) ---------------------------------------
SET_OPTION(LSPLANT_BUILD_SHARED OFF)
add_subdirectory(
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/LSPlant/lsplant/src/main/jni
        lsplant_build
)

add_library(qself_hook SHARED
        jni/qself_hook.cpp
)

target_include_directories(qself_hook PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/LSPlant/lsplant/src/main/jni
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../libs/Dobby
)

target_link_libraries(qself_hook PRIVATE
        lsplant_static
        dobby
        ${ANDROID_LIBS}
)

# keep the .so relocatable on 16KB-page devices
target_link_options(qself_hook PRIVATE -Wl,-z,max-page-size=16384)
