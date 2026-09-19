#!/usr/bin/env bash
# Publishes the installed toolchain as an annotation: when a build fails on a
# toolchain mismatch, this says which versions actually ran.
#
# SPDX-License-Identifier: GPL-3.0-or-later
set -uo pipefail

SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
NDK=$(ls "$SDK/ndk" 2>/dev/null | tr '\n' ',' || true)
CMAKE=$(ls "$SDK/cmake" 2>/dev/null | tr '\n' ',' || true)
PLATFORMS=$(ls "$SDK/platforms" 2>/dev/null | tr '\n' ',' || true)
BUILD_TOOLS=$(ls "$SDK/build-tools" 2>/dev/null | tr '\n' ',' || true)
NINJA=$(ninja --version 2>/dev/null || echo none)

echo "ndk=[$NDK] cmake=[$CMAKE] ninja=$NINJA platforms=[$PLATFORMS] build-tools=[$BUILD_TOOLS]"
echo "::notice title=toolchain::ndk=$NDK cmake=$CMAKE ninja=$NINJA platforms=$PLATFORMS build-tools=$BUILD_TOOLS"
