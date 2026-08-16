#!/usr/bin/env python3
"""
导入标注样本到JSON文件，供 train_model.py 使用。
输入格式：CSV，列为 content,label
"""
from __future__ import annotations

import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "logs" / "labeled_dataset.json"


def main() -> None:
    src = ROOT / "logs" / "labeled_dataset.csv"
    if not src.exists():
        print(f"missing: {src}")
        return
    rows = []
    with src.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append({"content": r.get("content", ""), "label": int(r.get("label", 0))})
    OUT.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"imported {len(rows)} rows -> {OUT}")


if __name__ == "__main__":
    main()
