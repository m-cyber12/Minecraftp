#!/usr/bin/env python3
"""Publish JUnit failures as GitHub check annotations and a job summary."""
import glob
import html
import os
import xml.etree.ElementTree as ET

failures = []
for filename in glob.glob("**/build/test-results/test/TEST-*.xml", recursive=True):
    try:
        root = ET.parse(filename).getroot()
    except (ET.ParseError, OSError):
        continue
    for case in root.iter("testcase"):
        for failure in list(case.findall("failure")) + list(case.findall("error")):
            detail = " ".join((failure.get("message", ""), failure.text or "")).strip()
            detail = html.unescape(detail)[:1200]
            failures.append((case.get("classname", "test"), case.get("name", "unknown"), detail))

if not failures:
    print("No failed JUnit test cases found in XML reports.")
    raise SystemExit(0)

summary = ["## Unit test failures", ""]
for classname, name, detail in failures:
    title = f"{classname}.{name}"
    message = (title + (": " + detail if detail else "")).replace("%", "%25")
    message = message.replace("\r", "%0D").replace("\n", "%0A").replace(":", "%3A").replace(",", "%2C")
    print(f"::error title=JUnit test failure::{message}")
    summary.extend([f"### `{title}`", "", "```text", detail or "No failure details.", "```", ""])

summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
if summary_path:
    with open(summary_path, "a", encoding="utf-8") as report:
        report.write("\n".join(summary) + "\n")
print(f"Published {len(failures)} failing JUnit test case(s).")
