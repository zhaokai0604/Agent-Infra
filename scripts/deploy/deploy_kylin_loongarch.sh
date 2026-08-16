#!/usr/bin/env bash
# ThreshCore — 银河麒麟高级服务器版 V11 + LoongArch64 生产构建
#
# 须在目标架构机器上执行（龙芯服务器或同 arch CI），交叉编译不在此脚本范围。
#
# 用法（仓库根目录）：
#   bash scripts/deploy/deploy_kylin_loongarch.sh
#
# 环境变量：
#   DEPLOY_OUT     默认 deploy/release-kylin
#   SKIP_FRONTEND  1 跳过前端
#   JAVA_HOME      须为 JDK 17（龙架构本地构建）
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

OUT="${DEPLOY_OUT:-$ROOT/deploy/release-kylin}"
export SKIP_TESTS=0
export PROD_BUILD=1

echo "======== 麒麟 V11 + LoongArch 生产构建 ========"
echo "uname: $(uname -a)"
echo ""

ARCH="$(uname -m)"
case "$ARCH" in
  loongarch64|mips64)
    echo "[arch] LoongArch/MIPS64 检测通过: $ARCH"
    ;;
  *)
    echo "[arch] 警告: 当前架构为 $ARCH，赛题交付目标为 loongarch64" >&2
    ;;
esac

if [[ -f /etc/os-release ]]; then
  echo "[os] $(grep -E '^(NAME|VERSION|ID)=' /etc/os-release | tr '\n' ' ')"
elif [[ -f /etc/.kyinfo ]]; then
  echo "[os] Kylin $(head -1 /etc/.kyinfo)"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "错误: 未找到 java，请安装 JDK 17（龙架构 build）" >&2
  exit 1
fi
java -version

need_cmds=(mvn node npm)
for c in "${need_cmds[@]}"; do
  if ! command -v "$c" >/dev/null 2>&1; then
    echo "错误: 缺少命令 $c" >&2
    exit 1
  fi
done

DEPLOY_OUT="$OUT" bash "$ROOT/scripts/deploy/deploy.sh"

echo ""
echo "打包麒麟部署附件..."
cp -f "$ROOT/scripts/deploy/env.kylin.prod.example" "$OUT/"
cp -f "$ROOT/scripts/deploy/award-log.service" "$OUT/"
cp -f "$ROOT/scripts/deploy/nginx.kylin.sample.conf" "$OUT/"
cp -f "$ROOT/scripts/deploy/sudoers.award-agent.example" "$OUT/"
cp -f "$ROOT/scripts/deploy/smoke_kylin_acceptance.sh" "$OUT/"
cp -f "$ROOT/scripts/deploy/setup_kylin_host.sh" "$OUT/"

cat > "$OUT/START-KYLIN.txt" << 'EOF'
麒麟 V11 + LoongArch 启动示例
=============================

0. 目标机环境准备（首次，需 root）
   sudo bash scripts/deploy/setup_kylin_host.sh
   # 或解压产物后: sudo bash setup_kylin_host.sh

1. 环境变量（见 env.kylin.prod.example → /etc/threshcore/threshcore.env）
   SPRING_PROFILES_ACTIVE=prod-kylin
   DB_URL=jdbc:mariadb://127.0.0.1:3306/log_analysis?...

2. 安装产物
   sudo cp award-log.jar /opt/threshcore/
   sudo cp -r dist /opt/threshcore/
   sudo cp award-log.service /etc/systemd/system/
   sudo systemctl daemon-reload && sudo systemctl enable --now award-log

3. Nginx（可选）
   sudo cp nginx.kylin.sample.conf /etc/nginx/conf.d/threshcore.conf
   sudo nginx -t && sudo systemctl reload nginx

4. 验收
   BASE=http://127.0.0.1:8088/award-log bash smoke_kylin_acceptance.sh
   # deliveryTargetMatch 应为 true

5. 手动探活
   curl -s http://127.0.0.1:8088/award-log/api/platform/info | jq .
EOF

echo ""
echo "产物目录: $OUT"
echo "======== 完成 ========"
