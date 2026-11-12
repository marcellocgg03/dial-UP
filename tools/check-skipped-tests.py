#!/usr/bin/env python3
"""Fails when a unit test was skipped that should have run.

Reads the JUnit XML under */build/test-results and lists every skipped test case. Two classes are allowed to skip because
they depend on the machine, not on what CI installs (a host with no non-loopback IPv4 address; a user who can read any file).
Anything else being skipped, for example the ffmpeg tests when ffmpeg is missing, is an error.
"""
import glob
import sys
import xml.etree.ElementTree as ET

ALLOWED_CLASSES = {
    "dev.t2u.dial.DialAccessTest",
    "dev.dialup.app.ConfigLoaderTest",
}

skipped = []
total = 0
for path in glob.glob("**/build/test-results/**/TEST-*.xml", recursive=True):
    for case in ET.parse(path).getroot().iter("testcase"):
        total += 1
        if case.find("skipped") is not None:
            skipped.append((case.get("classname"), case.get("name")))

unexpected = [s for s in skipped if s[0] not in ALLOWED_CLASSES]
print(f"{total} test cases, {len(skipped)} skipped, {len(unexpected)} not allowed to skip")
for cls, name in unexpected:
    print(f"  SKIPPED: {cls} > {name}")
sys.exit(1 if unexpected else 0)
