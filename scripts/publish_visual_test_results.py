#!/usr/bin/env python3
"""Publish small deterministic native-view test renders when artifact upload is unavailable.
Only explicitly named test fixtures under the supplied build report directory are read.
"""
import base64
from pathlib import Path
import sys

root = Path(sys.argv[1])
for name in ("home-light", "home-dark", "home-large-text", "feature-cells"):
    path = root / (name + ".jpg")
    if not path.is_file():
        continue
    image = path.read_bytes()
    if len(image) > 300_000:
        raise RuntimeError("Unexpectedly large test render: " + name)
    data = base64.b64encode(image).decode("ascii")
    chunks = [data[i:i + 6000] for i in range(0, len(data), 6000)]
    for index, chunk in enumerate(chunks, 1):
        print(f"::notice title=Qself UI render {name} {index}/{len(chunks)}::{chunk}")

# Surface nested Android runtime causes, which the workflow's short error summaries omit.
import xml.etree.ElementTree as ET
xml = root.parent.parent / "test-results/testDebugUnitTest/TEST-sumicya.qself.ui.SettingsVisualTest.xml"
if xml.is_file():
    results = ET.parse(xml).getroot()
    print(f"::notice title=Qself native view tests::tests={results.get('tests')} failures={results.get('failures')} errors={results.get('errors')}")
    for case in results.findall("testcase"):
        failure = case.find("failure")
        if failure is None:
            continue
        lines = (failure.text or "").splitlines()
        relevant = [line for line in lines if not line.lstrip().startswith("at ")]
        message = " | ".join(relevant)[:6000].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::notice title=Qself native test {case.get('name')}::{message}")
