#!/usr/bin/env bash
# ThreshCore / award-log 一键构建：前端 dist + 后端可执行 Jar，输出到 deploy/release/
#
# 用法（在仓库根目录执行）：
#   bash scripts/deploy/deploy.sh
#   SKIP_FRONTEND=1 bash scripts/deploy/deploy.sh   # 只打 Jar
#   SKIP_BACKEND=1  bash scripts/deploy/deploy.sh   # 只打前端
#
# 环境变量：
#   DEPLOY_OUT   输出目录，默认 <项目根>/deploy/release
#   SKIP_TESTS   设为 0 时 Maven 会执行测试（默认跳过测试）
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

OUT="${DEPLOY_OUT:-$ROOT/deploy/release}"
SKIP_FRONTEND="${SKIP_FRONTEND:-0}"
SKIP_BACKEND="${SKIP_BACKEND:-0}"
# PROD_BUILD=1 或 KYLIN_PROD_BUILD=1 时强制跑测试
if [[ "${PROD_BUILD:-0}" == "1" || "${KYLIN_PROD_BUILD:-0}" == "1" ]]; then
  SKIP_TESTS=0
else
  SKIP_TESTS="${SKIP_TESTS:-1}"
fi

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "错误: 未找到命令「$1」，请先安装并加入 PATH。" >&2
    exit 1
  fi
}

echo "======== ThreshCore 一键部署构建 ========"
echo "项目根: $ROOT"
echo "输出目录: $OUT"
echo ""

if [[ "$SKIP_FRONTEND" != "1" ]]; then
  need_cmd node
  need_cmd npm
  echo "[1/2] 构建前端 (frontend) ..."
  cd "$ROOT/frontend"
  if [[ -f package-lock.json ]]; then
    npm ci
  else
    npm install
  fi
  npm run build
  cd "$ROOT"
else
  echo "[1/2] 跳过前端 (SKIP_FRONTEND=1)"
fi

if [[ "$SKIP_BACKEND" != "1" ]]; then
  need_cmd mvn
  echo "[2/2] 构建后端 (Maven package) ..."
  if [[ "$SKIP_TESTS" == "0" ]]; then
    mvn -q package
  else
    mvn -q -DskipTests package
  fi
  cd "$ROOT"
else
  echo "[2/2] 跳过后端 (SKIP_BACKEND=1)"
fi

echo ""
echo "整理产物到: $OUT"
rm -rf "$OUT"
mkdir -p "$OUT/dist"

if [[ "$SKIP_FRONTEND" != "1" ]]; then
  cp -a "$ROOT/frontend/dist/." "$OUT/dist/"
fi

if [[ "$SKIP_BACKEND" != "1" ]]; then
  MAIN_JAR=""
  shopt -s nullglob
  for f in "$ROOT"/target/award-log-*.jar; do
    case "$f" in
      *-sources.jar|*-javadoc.jar) continue ;;
      *) MAIN_JAR="$f"; break ;;
    esac
  done
  shopt -u nullglob
  if [[ -z "$MAIN_JAR" || ! -f "$MAIN_JAR" ]]; then
    echo "错误: 未在 target/ 下找到 award-log-*.jar，请确认 mvn package 已成功。" >&2
    exit 1
  fi
  cp -f "$MAIN_JAR" "$OUT/award-log.jar"
  echo "已复制: $(basename "$MAIN_JAR") -> $OUT/award-log.jar"
fi

cp -f "$ROOT/scripts/deploy/nginx.sample.conf" "$OUT/nginx.sample.conf"

cat > "$OUT/README-DEPLOY.txt" << 'EOF'
ThreshCore (award-log) 部署说明
================================

1) 启动后端（需已配置 MariaDB 等，见 application.yml / 环境变量）

   java -jar award-log.jar

   默认端口 8088，上下文路径 /award-log
   生产建议: java -jar -Dspring.profiles.active=prod award-log.jar

2) 前端静态资源

   本目录 dist/ 可交给 Nginx root，并把浏览器对 /award-log 的请求反代到后端，示例见 nginx.sample.conf。

3) 前端 API 地址

   默认前端使用「当前域名 + 8088 端口」访问后端。若你用 Nginx 在 80 端口同域反代 /award-log，
   请同步修改 frontend/src/api/index.js 中的 baseURL，或让用户从 http://主机:8088 打开页面。

4) 数据与输出

   分析产物默认在进程工作目录下 target/output/<taskId>/，部署时注意磁盘与备份策略。
EOF

echo ""
echo "完成。产物:"
ls -la "$OUT" || true
echo "======== 结束 ========"
