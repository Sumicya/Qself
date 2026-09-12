#!/usr/bin/env python3
"""Publish deterministic native-view evidence, never user/device data.
GitHub limits notices per step and truncates long annotation messages. One compressed
contact sheet is deliberately bounded to 24 KiB, with chunks smaller than 4096 chars.
Full-resolution test renders remain in the runner's build/reports/qself-visual folder.
"""
import base64
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
xml = root.parent.parent / "test-results/testDebugUnitTest/TEST-sumicya.qself.ui.SettingsVisualTest.xml"
results = ET.parse(xml).getroot() if xml.is_file() else None
if results is not None:
    merged = [ET.parse(p).getroot() for p in xml.parent.glob("TEST-sumicya.qself.feature.consolidation.*.xml")]
    totals = {key: sum(int(report.get(key, 0)) for report in merged) for key in ("tests", "failures", "errors")}
    policy = [ET.parse(p).getroot() for pattern in ("TEST-sumicya.qself.diagnostics.FeatureErrorMetadataTest.xml", "TEST-sumicya.qself.glass.GlassConfigTest.xml") for p in xml.parent.glob(pattern)]
    policy_totals = {key: sum(int(report.get(key, 0)) for report in policy) for key in ("tests", "failures", "errors")}
    print(f"::notice title=Qself native view tests::tests={results.get('tests')} failures={results.get('failures')} errors={results.get('errors')} mergeTests={totals['tests']} mergeFailures={totals['failures']} mergeErrors={totals['errors']} policyTests={policy_totals['tests']} policyFailures={policy_totals['failures']} policyErrors={policy_totals['errors']}")
path = root / "options-pair.webp"
if not path.is_file():
    path = root / "home-pair.webp"
if path.is_file():
    image = path.read_bytes()
    if len(image) > 24000:
        raise RuntimeError("Test contact sheet exceeds annotation budget")
    data = base64.b64encode(image).decode("ascii")
    chunks = [data[i:i + 3800] for i in range(0, len(data), 3800)]
    for index, chunk in enumerate(chunks, 1):
        print(f"::notice title=Qself UI render {path.stem} {index}/{len(chunks)}::{chunk}")
if results is not None:
    for case in results.findall("testcase"):
        failure = case.find("failure")
        if failure is None:
            continue
        lines = (failure.text or "").splitlines()
        relevant = [line for line in lines if not line.lstrip().startswith("at ")]
        message = " | ".join(relevant)[:3800].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::notice title=Qself native test {case.get('name')}::{message}")
