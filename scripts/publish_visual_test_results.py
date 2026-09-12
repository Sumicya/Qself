#!/usr/bin/env python3
"""Publish small deterministic native-view test renders when artifact upload is unavailable.
Only explicitly named test fixtures under the supplied build report directory are read.
"""
import base64
from pathlib import Path
import sys

root = Path(sys.argv[1])
for name in ("home-light", "home-dark", "home-large-text"):
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
