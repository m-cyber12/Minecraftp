#!/usr/bin/env python3
"""Surface actionable Gradle packaging failures in the check annotations."""
import os
import re
import sys

lines = open(sys.argv[1], encoding="utf-8", errors="replace").read().splitlines()
patterns = re.compile(r"(What went wrong:|Execution failed|Could not |Could not find|No such task|A problem occurred|Cannot |Caused by:|FAILED$|failed to |error:)", re.I)
selected = []
for line in lines:
    if patterns.search(line.strip()):
        selected.append(line.strip())
if not selected:
    selected = lines[-40:]
selected = selected[-30:]
summary = ["## Gradle packaging diagnostics", "", "```text", *selected, "```", ""]
path = os.environ.get("GITHUB_STEP_SUMMARY")
if path:
    with open(path, "a", encoding="utf-8") as output:
        output.write("\n".join(summary))
for line in selected:
    message = line.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    message = message.replace(":", "%3A").replace(",", "%2C")
    print(f"::error title=Gradle packaging failure::{message}")
