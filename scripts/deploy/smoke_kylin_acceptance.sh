#!/usr/bin/env bash
# 麒麟 V11 + LoongArch 验收冒烟（服务已启动）
# 用法: BASE=http://127.0.0.1:8088/award-log bash scripts/deploy/smoke_kylin_acceptance.sh
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8088/award-log}"

echo "== 1. Actuator health =="
curl -sf "$BASE/actuator/health" | head -c 500
echo ""

echo "== 2. Platform acceptance (公开验收) =="
INFO=$(curl -sf "$BASE/api/platform/acceptance")
echo "$INFO" | head -c 2000
echo ""

if echo "$INFO" | grep -q '"deliveryTargetMatch":true'; then
  echo "[PASS] deliveryTargetMatch=true"
else
  echo "[WARN] deliveryTargetMatch 非 true（请确认运行在麒麟 LoongArch 目标机）"
fi

echo "== 3. MCP tools 注册 =="
curl -sf "$BASE/api/mcp/tools" | grep -q AutonomousOpsTool && echo "[PASS] AutonomousOpsTool 已注册" || echo "[FAIL] 缺少 AutonomousOpsTool"

echo "== 4. 自主运维只读 =="
curl -sf -X POST "$BASE/api/ops/autonomous/run?readOnly=true" | head -c 800
echo ""
echo "== 完成 =="
