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

echo "ndk=[$NDK] cmake=[$CMAKE] platforms=[$PLATFORMS] build-tools=[$BUILD_TOOLS]"
echo "::notice title=toolchain::ndk=$NDK%0Acmake=$CMAKE%0Aplatforms=$PLATFORMS%0Abuild-tools=$BUILD_TOOLS"
