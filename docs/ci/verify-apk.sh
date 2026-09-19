#!/usr/bin/env bash
# Verifies the module contract inside the built APK, and reports the result as
# an annotation (so it can be inspected without downloading the artifact).
#
# Two things no compiler catches:
#  - the modern (libxposed) contract is a set of files in META-INF/xposed; a
#    wrong path means "installs but never loads";
#  - the framework API classes are compileOnly stubs whose bodies throw
#    AssertionError("STUB") — bundling them would shadow the framework.
#
# SPDX-License-Identifier: GPL-3.0-or-later
set -uo pipefail

SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"

APK=$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
if [ -z "$APK" ]; then
  echo "::error title=apk-contents::no APK was produced"
  exit 1
fi
SIZE=$(stat -c%s "$APK")
LISTING=$(unzip -l "$APK")

fail=0
report=""
for entry in META-INF/xposed/module.prop META-INF/xposed/java_init.list \
             META-INF/xposed/scope.list lib/arm64-v8a/libqself_hook.so \
             lib/armeabi-v7a/libqself_hook.so; do
  if grep -q " ${entry}$" <<<"$LISTING"; then
    report="${report}OK ${entry}, "
  else
    report="${report}MISSING ${entry}, "
    fail=1
  fi
done

# The entry class must be present in one of the dex images: the descriptor
# string is part of the dex string table.
ENTRY=$(grep -m1 -v '^[[:space:]]*#' \
  tools/moduleprop/src/main/resources/META-INF/xposed/java_init.list 2>/dev/null | tr -d '[:space:]' || true)
ENTRY=${ENTRY:-sumicya.qself.QselfModule10x}
DESCRIPTOR="L${ENTRY//.//};"
DEXES=$(grep -oE 'classes[0-9]*\.dex$' <<<"$LISTING" | sort -u | tr '\n' ' ')
entry_found=no
for dex in $DEXES; do
  if unzip -p "$APK" "$dex" | grep -aqF "$DESCRIPTOR"; then
    entry_found=yes
    break
  fi
done
if [ "$entry_found" = yes ]; then
  report="${report}OK ${ENTRY} in [${DEXES}], "
else
  report="${report}MISSING ${ENTRY} in [${DEXES}], "
  fail=1
fi

# No framework API classes in the dex images.
DEXDUMP=$(ls "$SDK"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$DEXDUMP" ]; then
  rm -rf dex-check
  mkdir -p dex-check
  unzip -o -q "$APK" 'classes*.dex' -d dex-check
  DESCRIPTORS=$("$DEXDUMP" dex-check/*.dex 2>/dev/null \
    | grep -o 'Class descriptor.*' || true)
  STUBS=$(grep -c 'Lio/github/libxposed/\|Lde/robv/android/xposed/' <<<"$DESCRIPTORS" || true)
  if [ "$STUBS" -eq 0 ]; then
    report="${report}OK no framework stubs bundled, "
  else
    report="${report}BUNDLED ${STUBS} framework stub classes, "
    fail=1
  fi
  # "Framework-only UI" is a claim; this is its proof.
  UI_LIBS=$(grep -c 'Landroidx/\|Lcom/google/android/material/' <<<"$DESCRIPTORS" || true)
  if [ "$UI_LIBS" -eq 0 ]; then
    report="${report}OK no AndroidX/Material classes, "
  else
    report="${report}BUNDLED ${UI_LIBS} AndroidX/Material classes, "
    fail=1
  fi
else
  report="${report}SKIP framework-stub check (no dexdump), "
fi

echo "apk: $APK ($SIZE bytes)"
echo "$report"
echo "::notice title=apk-contents::${SIZE} bytes | ${report}"
echo "::notice title=module.prop::$(tr '\n' ' ' < tools/moduleprop/src/main/resources/META-INF/xposed/module.prop)"

if [ "$fail" -ne 0 ]; then
  echo "::error title=apk-contents::module contract not satisfied: ${report}"
  exit 1
fi
