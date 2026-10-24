#!/usr/bin/env python3
"""Publish javac diagnostics as GitHub check annotations."""
import re
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8", errors="replace").read()
# Gradle's plain console uses javac's `file.java:line: error: ...` format.
pattern = re.compile(r"^(?P<file>[^\s].*?\.java):(?P<line>\d+):\s+error:\s+(?P<message>.+)$")
count = 0
lines = text.splitlines()
for index, line in enumerate(lines):
    match = pattern.match(line)
    if not match:
        continue
    details = [match.group("message")]
    # Include javac's symbol/location detail, but stop before the next source
    # location or Gradle task summary to keep each annotation focused.
    for following in lines[index + 1 : index + 5]:
        if pattern.match(following) or following.startswith("> Task "):
            break
        if following.strip():
            details.append(following.strip())
    message = " — ".join(details)
    message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    message = message.replace(":", "%3A").replace(",", "%2C")
    source = match.group("file").replace("%", "%25").replace(",", "%2C").replace(":", "%3A")
    print(f"::error file={source},line={match.group('line')},title=Java compile error::{message}")
    count += 1

if count == 0:
    print("No javac-style diagnostics found; full Gradle output follows in the failed step.")
else:
    print(f"Published {count} Java compiler diagnostic(s) as check annotations.")
