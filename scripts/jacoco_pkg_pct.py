import os
import re
import sys

base = os.path.join(os.path.dirname(__file__), "..", "target", "site", "jacoco")
packages = sys.argv[1:] if len(sys.argv) > 1 else [
    "com.award.log.common",
    "com.award.log.trace",
    "com.award.log.platform",
]

for pkg in packages:
    path = os.path.join(base, pkg, "index.html")
    if not os.path.isfile(path):
        print(f"{pkg}: missing")
        continue
    text = open(path, encoding="utf-8").read()
    foot = re.search(r"<tfoot>.*?Total.*?</tfoot>", text, re.S)
    if not foot:
        print(f"{pkg}: no footer")
        continue
    pcts = re.findall(r'class="ctr2">(\d+)%', foot.group(0))
    labels = ["instruction", "branch", "complexity?", "line", "method", "class"]
    print(f"{pkg}: {dict(zip(labels[:len(pcts)], pcts))}")
