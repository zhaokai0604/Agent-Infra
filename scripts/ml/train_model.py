#!/usr/bin/env python3
"""
随机森林训练脚本（25维特征）。
特征布局必须与 Java RfFeatureVectorExt.fromDecisionInputExt 一致：
  0-4   级别 one-hot TRACE/DEBUG/INFO/WARN/ERROR|FATAL
  5-8   时间间隔（norm, norm^2, norm, 0）
  9-13  模板 token 哈希桶直方图（归一化）
  14-16 error/exception/timeout 关键词分
  17-19 errorRate1m / error1m/100 / total1m/300
  20    调用栈深度（" at "）
  21    字符熵
  22-24 db / http / other 协议 one-hot
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List, Tuple

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (
    classification_report,
    confusion_matrix,
    precision_recall_fscore_support,
)
from sklearn.model_selection import StratifiedKFold, train_test_split
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType


ROOT = Path(__file__).resolve().parents[2]
LOG_DIR = ROOT / "logs"
MODEL_DIR = ROOT / "src" / "main" / "resources" / "models"
MODEL_DIR.mkdir(parents=True, exist_ok=True)
MODEL_PATH = MODEL_DIR / "random-forest-v2.onnx"


def load_from_logs() -> List[Dict[str, Any]]:
    samples: List[Dict[str, Any]] = []
    for p in LOG_DIR.glob("**/*.log*"):
        try:
            if p.suffix == ".gz":
                with gzip.open(p, "rt", encoding="utf-8", errors="ignore") as f:
                    lines = [l.strip() for l in f if l.strip()]
            else:
                with p.open("r", encoding="utf-8", errors="ignore") as f:
                    lines = [l.strip() for l in f if l.strip()]

            for line in lines:
                label = 1 if any(
                    k in line.lower()
                    for k in ["error", "fatal", "exception", "timeout", "panic", "crash"]
                ) else 0
                samples.append(
                    {
                        "content": line,
                        "label": label,
                        "level": infer_level(line),
                        "template": "",
                        "confidence": 0.0,
                        "error_rate_1m": 0.0,
                        "error_1m": 0.0,
                        "total_1m": 0.0,
                        "interval_ms": 0.0,
                    }
                )
        except Exception as e:
            print(f"跳过文件 {p}: {e}")
            continue
    return samples


def load_from_jsonl(jsonl_path: Path) -> List[Dict[str, Any]]:
    samples: List[Dict[str, Any]] = []
    with jsonl_path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                sample = json.loads(line)
                samples.append(
                    {
                        "content": sample.get("content", ""),
                        "label": int(sample.get("label", 0)),
                        "level": sample.get("level", "INFO"),
                        "template": sample.get("template", ""),
                        "confidence": float(sample.get("confidence", 0.0)),
                        "error_rate_1m": float(sample.get("error_rate_1m", sample.get("errorRate1m", 0.0))),
                        "error_1m": float(sample.get("error_1m", sample.get("error1m", 0.0))),
                        "total_1m": float(sample.get("total_1m", sample.get("total1m", 0.0))),
                        "interval_ms": float(sample.get("interval_ms", sample.get("intervalMs", 0.0))),
                    }
                )
            except Exception as e:
                print(f"跳过无效行: {e}")
                continue
    return samples


def infer_level(line: str) -> str:
    s = line.lower()
    if "fatal" in s or "critical" in s:
        return "FATAL"
    if "error" in s:
        return "ERROR"
    if "warn" in s or "warning" in s:
        return "WARN"
    if "debug" in s:
        return "DEBUG"
    if "trace" in s:
        return "TRACE"
    return "INFO"


def calculate_entropy(text: str) -> float:
    """与 Java RfFeatureVectorExt.entropy 对齐：仅 ASCII，/7 归一化。"""
    if not text:
        return 0.0
    freq = [0] * 128
    n = 0
    for ch in text:
        code = ord(ch)
        if code < 128:
            freq[code] += 1
            n += 1
    if n == 0:
        return 0.0
    entropy = 0.0
    for f in freq:
        if f == 0:
            continue
        p = f / n
        entropy -= p * (np.log2(p) if p > 0 else 0)
    return float(min(1.0, entropy / 7.0))


def keyword_score(text: str, keyword: str) -> float:
    if not text:
        return 0.0
    return float(min(1.0, text.count(keyword) / 3.0))


def build_feature_vector(sample: Dict[str, Any]) -> List[float]:
    """与 Java RfFeatureVectorExt.fromDecisionInputExt 一一对应。"""
    content = (sample.get("content") or "").lower()
    template = (sample.get("template") or "").lower()
    level_str = (sample.get("level") or "INFO").upper()

    data = [0.0] * 25
    data[0] = 1.0 if "TRACE" in level_str else 0.0
    data[1] = 1.0 if "DEBUG" in level_str else 0.0
    data[2] = 1.0 if "INFO" in level_str else 0.0
    data[3] = 1.0 if "WARN" in level_str else 0.0
    data[4] = 1.0 if ("ERROR" in level_str or "FATAL" in level_str) else 0.0

    interval_ms = max(0.0, float(sample.get("interval_ms", 0.0)))
    norm = float(min(1.0, interval_ms / 10000.0))
    data[5] = norm
    data[6] = norm * norm
    data[7] = norm
    data[8] = 0.0

    for token in template.split():
        if not token:
            continue
        # Java Math.abs(token.hashCode()) % 5 —— Python 用等价稳定哈希近似
        idx = abs(hash(token)) % 5
        data[9 + idx] += 1.0
    bucket_sum = sum(data[9:14])
    if bucket_sum > 0:
        for i in range(9, 14):
            data[i] /= bucket_sum

    data[14] = keyword_score(content, "error")
    data[15] = keyword_score(content, "exception")
    data[16] = keyword_score(content, "timeout")

    error_rate = float(sample.get("error_rate_1m", 0.0))
    error_1m = float(sample.get("error_1m", 0.0))
    total_1m = float(sample.get("total_1m", 0.0))
    data[17] = error_rate
    data[18] = float(min(1.0, error_1m / 100.0))
    data[19] = float(min(1.0, total_1m / 300.0))

    data[20] = float(min(1.0, content.count(" at ") / 30.0))
    data[21] = calculate_entropy(content)

    db = 1.0 if any(k in content for k in ("mysql", "sql", "jdbc")) else 0.0
    http = 1.0 if any(k in content for k in ("http", "nginx", "apache", "status")) else 0.0
    data[22] = db
    data[23] = http
    data[24] = 1.0 if (db == 0.0 and http == 0.0) else 0.0
    return data


def build_features(samples: List[Dict[str, Any]]) -> Tuple[np.ndarray, np.ndarray]:
    rows: List[List[float]] = []
    labels: List[int] = []
    for sample in samples:
        rows.append(build_feature_vector(sample))
        labels.append(int(sample.get("label", 0)))
    return np.array(rows, dtype=np.float32), np.array(labels, dtype=np.int64)


def main() -> None:
    parser = argparse.ArgumentParser(description="训练随机森林模型")
    parser.add_argument("--training-data", type=str, help="标注数据JSONL文件路径")
    parser.add_argument(
        "--min-samples",
        type=int,
        default=3000,
        help="最小样本数（不足时扩充；仅用于CV稳定性，可能虚高CV）",
    )
    args = parser.parse_args()

    print("=" * 60)
    print("随机森林模型训练 - 与 RfFeatureVectorExt 对齐")
    print("=" * 60)

    if args.training_data:
        jsonl_path = Path(args.training_data)
        if not jsonl_path.exists():
            print(f"错误: 文件不存在 {jsonl_path}", file=sys.stderr)
            sys.exit(1)
        print(f"从标注文件加载样本: {jsonl_path}")
        samples = load_from_jsonl(jsonl_path)
    else:
        if not LOG_DIR.exists():
            print(f"错误: logs 目录不存在 {LOG_DIR}", file=sys.stderr)
            sys.exit(1)
        print("从 logs 目录自动构建样本")
        samples = load_from_logs()

    print(f"原始样本数: {len(samples)}")
    if len(samples) == 0:
        print("错误: 无可用训练样本", file=sys.stderr)
        sys.exit(1)

    # 不足时复制扩充（保持原行为，但打印警告）
    if len(samples) < args.min_samples:
        print(f"警告: 样本不足 {args.min_samples}，将重复扩充（CV 指标可能虚高）")
        base = list(samples)
        while len(samples) < args.min_samples:
            samples.extend(base)
        samples = samples[: args.min_samples]

    X, y = build_features(samples)
    print(f"特征矩阵: {X.shape}, 正样本率: {y.mean():.4f}")

    if len(np.unique(y)) < 2:
        print("错误: 标签只有单一类别，无法训练分类器", file=sys.stderr)
        sys.exit(1)

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    clf = RandomForestClassifier(
        n_estimators=200,
        max_depth=12,
        min_samples_leaf=2,
        class_weight="balanced",
        random_state=42,
        n_jobs=-1,
    )
    clf.fit(X_train, y_train)

    y_pred = clf.predict(X_test)
    p, r, f1, _ = precision_recall_fscore_support(y_test, y_pred, average="binary", zero_division=0)
    print(f"Holdout P/R/F1: {p:.4f}/{r:.4f}/{f1:.4f}")
    print("Confusion:\n", confusion_matrix(y_test, y_pred))
    print(classification_report(y_test, y_pred, zero_division=0))

    try:
        skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
        scores = []
        for tr, te in skf.split(X, y):
            c = RandomForestClassifier(
                n_estimators=100, max_depth=12, class_weight="balanced", random_state=42, n_jobs=-1
            )
            c.fit(X[tr], y[tr])
            pred = c.predict(X[te])
            _, _, fold_f1, _ = precision_recall_fscore_support(
                y[te], pred, average="binary", zero_division=0
            )
            scores.append(fold_f1)
        print(f"5-fold F1 mean={np.mean(scores):.4f} std={np.std(scores):.4f}")
    except Exception as e:
        print(f"交叉验证跳过: {e}")

    initial_type = [("input", FloatTensorType([None, 25]))]
    onnx_model = convert_sklearn(clf, initial_types=initial_type, target_opset=12)
    with MODEL_PATH.open("wb") as f:
        f.write(onnx_model.SerializeToString())
    print(f"已导出 ONNX: {MODEL_PATH}")
    print("=" * 60)


if __name__ == "__main__":
    main()
