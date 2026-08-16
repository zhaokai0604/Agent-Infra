#!/usr/bin/env python3
"""
loghub 优化版训练脚本
目标：在 loghub 数据集上冲到 88-91% F1
"""
from __future__ import annotations

import argparse
import gzip
import re
from pathlib import Path
from typing import List, Tuple, Dict, Any

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics import (
    precision_recall_fscore_support,
    confusion_matrix,
    classification_report
)
from sklearn.model_selection import StratifiedKFold, train_test_split
from sklearn.preprocessing import StandardScaler
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType


ROOT = Path(__file__).resolve().parents[2]
LOG_DIR = ROOT / "logs"
MODEL_DIR = ROOT / "src" / "main" / "resources" / "models"
MODEL_DIR.mkdir(parents=True, exist_ok=True)
MODEL_PATH = MODEL_DIR / "random-forest-v2.onnx"


def load_loghub_data(dataset_name: str = "HDFS") -> Tuple[List[str], List[int]]:
    """
    加载 loghub 格式数据
    支持：HDFS, BGL, Spark, Zookeeper 等
    """
    print(f"加载 loghub {dataset_name} 数据集...")
    
    log_file = LOG_DIR / f"{dataset_name}.log"
    label_file = LOG_DIR / f"{dataset_name}_anomaly_label.csv"
    
    logs: List[str] = []
    labels: List[int] = []
    
    if log_file.exists() and label_file.exists():
        print(f"  从 {log_file} 和 {label_file} 加载")
        log_lines = {}
        with log_file.open("r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                parts = line.strip().split()
                if len(parts) >= 2:
                    log_id = parts[0]
                    content = " ".join(parts[1:])
                    log_lines[log_id] = content
        
        with label_file.open("r", encoding="utf-8") as f:
            next(f)
            for line in f:
                parts = line.strip().split(",")
                if len(parts) >= 2:
                    log_id = parts[0]
                    label = 1 if parts[1].strip() == "Anomaly" else 0
                    if log_id in log_lines:
                        logs.append(log_lines[log_id])
                        labels.append(label)
    
    else:
        print(f"  loghub 文件不存在，从 logs 目录自动构建...")
        for p in LOG_DIR.glob("**/*.log*"):
            try:
                if p.suffix == ".gz":
                    with gzip.open(p, "rt", encoding="utf-8", errors="ignore") as f:
                        lines = [l.strip() for l in f if l.strip()]
                else:
                    with p.open("r", encoding="utf-8", errors="ignore") as f:
                        lines = [l.strip() for l in f if l.strip()]
                
                for line in lines:
                    label = 1 if any(k in line.lower() for k in 
                                    ["error", "fatal", "exception", "timeout", "panic", "crash", "anomaly"]) else 0
                    logs.append(line)
                    labels.append(label)
            except Exception as e:
                print(f"跳过文件 {p}: {e}")
                continue
    
    print(f"  加载完成，总样本数: {len(logs)}")
    print(f"  异常样本: {sum(labels)}, 正常样本: {len(labels)-sum(labels)}")
    return logs, labels


def extract_advanced_features(logs: List[str]) -> np.ndarray:
    """
    改进版特征提取（45维）
    专门针对 loghub 优化
    """
    features_list: List[List[float]] = []
    
    # TF-IDF 向量化（最重要！）
    print("  计算 TF-IDF 特征...")
    tfidf = TfidfVectorizer(
        max_features=20,  # 取 Top 20 关键词
        ngram_range=(1, 2),  # 1-gram + 2-gram
        stop_words="english",
        min_df=3,
        max_df=0.9
    )
    tfidf_matrix = tfidf.fit_transform(logs).toarray()
    
    for idx, line in enumerate(logs):
        s = line.lower()
        features: List[float] = []
        
        # 1. 日志级别特征 (0-4)
        level_features = [
            1.0 if "trace" in s else 0.0,
            1.0 if "debug" in s else 0.0,
            1.0 if "info" in s else 0.0,
            1.0 if any(k in s for k in ["warn", "warning"]) else 0.0,
            1.0 if any(k in s for k in ["error", "fatal", "critical", "panic"]) else 0.0,
        ]
        features.extend(level_features)
        
        # 2. 统计特征 (5-14)
        line_len = len(line)
        word_count = len(line.split())
        num_tokens = len(re.findall(r'\b\w+\b', line))
        
        stats_features = [
            min(1.0, line_len / 2000.0),  # 日志长度
            min(1.0, word_count / 100.0),   # 词数
            min(1.0, num_tokens / 80.0),     # token 数
            min(1.0, line.count(' ') / 50.0),  # 空格数
            min(1.0, (line.count('/') + line.count('\\')) / 20.0),  # 路径数
        ]
        features.extend(stats_features)
        
        # 3. 关键词特征 (15-24)
        keyword_features = [
            min(1.0, s.count("error") / 2.0),
            min(1.0, s.count("exception") / 2.0),
            min(1.0, s.count("failed") / 2.0),
            min(1.0, s.count("timeout") / 1.0),
            min(1.0, s.count("null") / 3.0),
            min(1.0, s.count("outofmemory") / 1.0),
            min(1.0, s.count("connection") / 2.0),
            min(1.0, s.count("terminated") / 1.0),
            min(1.0, s.count("dead") / 1.0),
            min(1.0, s.count("crash") / 1.0),
        ]
        features.extend(keyword_features)
        
        # 4. 结构特征 (25-29)
        structure_features = [
            1.0 if " at " in s else 0.0,  # Java 堆栈
            1.0 if any(k in s for k in ["caused by:", "exception:"]) else 0.0,  # 异常链
            1.0 if any(k in s for k in ["192.168.", "10.", "172."]) else 0.0,  # IP
            1.0 if any(k in s for k in ["http://", "https://"]) else 0.0,  # URL
            min(1.0, s.count(":") / 10.0),  # 冒号数（JSON/键值对）
        ]
        features.extend(structure_features)
        
        # 5. 熵特征 (30)
        entropy = calculate_entropy(line)
        features.append(entropy)
        
        # 6. TF-IDF 特征 (31-50) - 最重要！
        tfidf_features = tfidf_matrix[idx].tolist()
        features.extend(tfidf_features)
        
        features_list.append(features[:50])  # 固定 50 维
    
    return np.array(features_list, dtype=np.float32)


def calculate_entropy(text: str) -> float:
    """计算字符分布熵"""
    if not text:
        return 0.0
    from collections import Counter
    freq = Counter(text)
    total = len(text)
    entropy = 0.0
    for count in freq.values():
        p = count / total
        entropy -= p * (np.log2(p) if p > 0 else 0)
    return min(1.0, entropy / 7.0)


def main() -> None:
    parser = argparse.ArgumentParser(description="loghub 优化版训练")
    parser.add_argument("--dataset", type=str, default="HDFS", 
                       help="loghub 数据集名称 (HDFS/BGL/Spark)")
    parser.add_argument("--min-samples", type=int, default=5000, 
                       help="最小样本数")
    args = parser.parse_args()
    
    print("=" * 70)
    print("loghub 优化版训练脚本 - 目标 F1 88-91%")
    print("=" * 70)
    
    logs, labels = load_loghub_data(args.dataset)
    
    if len(logs) < args.min_samples:
        print(f"样本数不足 {args.min_samples}，通过重复扩充...")
        multiplier = (args.min_samples // max(1, len(logs))) + 1
        logs = (logs * multiplier)[:args.min_samples]
        labels = (labels * multiplier)[:args.min_samples]
        print(f"扩充后样本数: {len(logs)}")
    
    print("\n构建特征向量 (50维)...")
    X = extract_advanced_features(logs)
    y = np.array(labels, dtype=np.int64)
    print(f"特征矩阵形状: {X.shape}")
    
    # 标准化
    print("\n标准化特征...")
    scaler = StandardScaler()
    X = scaler.fit_transform(X)
    
    print("\n初始化随机森林（优化版）...")
    clf = RandomForestClassifier(
        n_estimators=600,
        max_depth=30,
        min_samples_split=2,
        min_samples_leaf=1,
        max_features="sqrt",
        bootstrap=True,
        class_weight="balanced_subsample",
        random_state=42,
        n_jobs=-1,
        verbose=1
    )
    
    print("\n执行 5 折交叉验证...")
    skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
    fold_results = []
    all_y_true = []
    all_y_pred = []
    
    for fold, (train_idx, test_idx) in enumerate(skf.split(X, y), 1):
        print(f"\n--- 第 {fold} 折 ---")
        X_train, X_test = X[train_idx], X[test_idx]
        y_train, y_test = y[train_idx], y[test_idx]
        
        clf.fit(X_train, y_train)
        y_pred = clf.predict(X_test)
        
        all_y_true.extend(y_test)
        all_y_pred.extend(y_pred)
        
        p, r, f, _ = precision_recall_fscore_support(
            y_test, y_pred, average="binary", zero_division=0
        )
        print(f"  Precision: {p:.4f}")
        print(f"  Recall:    {r:.4f}")
        print(f"  F1:        {f:.4f}")
        
        cm = confusion_matrix(y_test, y_pred)
        print(f"  混淆矩阵:\n{cm}")
        
        fold_results.append((p, r, f))
    
    print("\n" + "=" * 70)
    print("交叉验证结果汇总")
    print("=" * 70)
    
    p_list, r_list, f_list = zip(*fold_results)
    
    print(f"\nPrecision: {np.mean(p_list):.4f} (±{np.std(p_list):.4f})")
    print(f"Recall:    {np.mean(r_list):.4f} (±{np.std(r_list):.4f})")
    print(f"F1:        {np.mean(f_list):.4f} (±{np.std(f_list):.4f})")
    
    print("\n整体分类报告:")
    print(classification_report(all_y_true, all_y_pred, digits=4))
    
    print("\nTop 20 特征重要性:")
    feature_names = [
        "levelTrace", "levelDebug", "levelInfo", "levelWarn", "levelErrorFatal",
        "lineLen", "wordCount", "numTokens", "spaces", "paths",
        "kwError", "kwException", "kwFailed", "kwTimeout", "kwNull", 
        "kwOOM", "kwConn", "kwTerm", "kwDead", "kwCrash",
        "hasStack", "hasExceptionChain", "hasIP", "hasURL", "colonCount",
        "entropy"
    ] + [f"tfidf{i}" for i in range(20)]
    
    importances = dict(zip(feature_names[:len(clf.feature_importances_)], 
                           clf.feature_importances_))
    
    for name, imp in sorted(importances.items(), 
                             key=lambda x: x[1], reverse=True)[:20]:
        print(f"  {name:20s}: {imp:.4f}")
    
    print("\n在完整数据集上训练最终模型...")
    clf.fit(X, y)
    
    print("\n导出 ONNX 模型...")
    onnx = convert_sklearn(
        clf,
        initial_types=[("float_input", FloatTensorType([None, X.shape[1]]))],
        target_opset=13
    )
    MODEL_PATH.write_bytes(onnx.SerializeToString())
    print(f"模型已导出 -> {MODEL_PATH}")
    
    print("\n" + "=" * 70)
    print("训练完成！")
    print("=" * 70)
    
    f_mean = np.mean(f_list)
    if f_mean >= 0.91:
        print(f"\n🎉 F1 = {f_mean:.1%} - 非常优秀！可以冲击一等奖！")
    elif f_mean >= 0.88:
        print(f"\n✅ F1 = {f_mean:.1%} - 很不错！二等奖很有希望！")
    elif f_mean >= 0.85:
        print(f"\n👍 F1 = {f_mean:.1%} - 还不错，三等奖稳了！")
    else:
        print(f"\n💪 F1 = {f_mean:.1%} - 继续优化，还能提升！")


if __name__ == "__main__":
    main()
