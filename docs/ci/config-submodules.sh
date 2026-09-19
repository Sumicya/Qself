#!/usr/bin/env bash
# Submodule preparation.
#
# LSPlant's CMake pulls in external/dex_builder (needed) and its test project
# references two SSH-only submodules (not needed, and unreachable from CI).
#
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

git -C libs/LSPlant config submodule.test/src/main/jni/external/lsprism.update none
git -C libs/LSPlant config submodule.test/src/main/jni/external/lsparself.update none
git -C libs/LSPlant config submodule.docs/doxygen-awesome-css.update none

git submodule foreach git submodule update --init --recursive
