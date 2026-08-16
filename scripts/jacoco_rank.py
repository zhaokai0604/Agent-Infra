import os
import re

base = os.path.join(os.path.dirname(__file__), "..", "target", "site", "jacoco")
rows = []
for name in os.listdir(base):
    p = os.path.join(base, name, "index.html")
    if not os.path.isfile(p):
        continue
    text = open(p, encoding="utf-8").read()
    foot = re.search(r"<tfoot>.*?Total.*?</tfoot>", text, re.S)
    if not foot:
        continue
    pct = re.search(r'class="ctr2">(\d+)%', foot.group(0))
    bar = re.search(r'class="bar">([\d,]+) of ([\d,]+)', foot.group(0))
    if pct and bar:
        missed = int(bar.group(1).replace(",", ""))
        total = int(bar.group(2).replace(",", ""))
        rows.append((int(pct.group(1)), missed, total, name))

rows.sort(key=lambda x: (-x[1], x[0]))
print("Top packages by missed instructions:")
for pct, missed, total, pkg in rows[:30]:
    print(f"{pct:3d}% missed={missed:6d} total={total:6d}  {pkg}")
