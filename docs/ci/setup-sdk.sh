#!/usr/bin/env bash
# Android SDK bits the build needs: licences, NDK r29 (Dobby + LSPlant) and
# CMake 3.31 (LSPlant's C++23 modules need 3.28+).
#
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

SDK="${ANDROID_HOME:?ANDROID_HOME is not set}"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

mkdir -p "$SDK/licenses"
echo -n 24333f8a63b6825ea9c5514f83c2829b004d1fee > "$SDK/licenses/android-sdk-license"
# `yes` gets SIGPIPE once sdkmanager is done, which pipefail would report as a
# failure — the licences are accepted either way.
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

"$SDKMANAGER" "ndk;29.0.13599879" "cmake;3.31.0"

# LSPlant's C++23 module targets need Ninja >= 1.11; the Android CMake package
# still bundles 1.10.2, so the build is pointed at the distro's Ninja through
# the override that native/build.gradle.kts reads.
sudo apt-get install -y --no-install-recommends ninja-build >/dev/null
echo "sdk.dir=$SDK" > local.properties
echo "qself.ninja.path=$(command -v ninja || echo /usr/bin/ninja)" >> local.properties
