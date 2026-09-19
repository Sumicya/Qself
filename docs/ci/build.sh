#!/usr/bin/env bash
# Builds the debug APK; on failure the Gradle errors are republished as
# check-run annotations.
#
# Why: the sandbox that maintains this repo cannot download job logs
# (results-receiver.actions.githubusercontent.com is unreachable), but the
# checks API is reachable, so failures have to report themselves.
#
# SPDX-License-Identifier: GPL-3.0-or-later
set -uo pipefail

./gradlew assembleDebug --stacktrace --console=plain > build.log 2>&1
code=$?

echo "gradle exit code: $code"
tail -n 60 build.log || true

if [ "$code" -ne 0 ]; then
  python3 - <<'PY'
import os

path = 'build.log'
data = open(path, errors='replace').read() if os.path.exists(path) else '(no build.log)'
keys = ('error:', 'e: ', 'FAILURE', 'FAILED', 'What went wrong', 'Caused by',
        'Could not', 'Unresolved reference', 'not found', 'Exception', 'Unable to')
picked, seen = [], set()
for line in data.splitlines():
    if any(k in line for k in keys) and line not in seen:
        seen.add(line)
        picked.append(line)

def emit(title, text):
    text = text.replace('%', '%25').replace('\r', '')
    if not text.strip():
        text = '(empty)'
    blocks = [text[i:i + 2800] for i in range(0, len(text), 2800)]
    for i, block in enumerate(blocks):
        print("::error title=%s %d/%d::%s" % (title, i + 1, len(blocks), block.replace('\n', '%0A')))

emit('gradle-errors', '\n'.join(picked[:120]))
emit('gradle-tail', data[-14000:])
PY
fi

exit "$code"
