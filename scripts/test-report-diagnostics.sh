#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! command -v javac >/dev/null; then
    echo 'JDK 17+ required (javac is missing); no Android SDK is needed for the metadata tests.' >&2
    exit 1
fi
javac --release 11 -d "$work" \
    app/src/main/java/sumicya/qself/diagnostics/ReportMetadata.java \
    app/src/test/java/sumicya/qself/diagnostics/ReportMetadataTest.java
java -cp "$work" sumicya.qself.diagnostics.ReportMetadataTest
python3 scripts/test_report_diagnostics_contract.py
