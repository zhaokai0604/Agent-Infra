#!/usr/bin/env bash
# 从已安装的 Jar + dist 继续部署（写环境变量 → 停旧后端 → systemd → Nginx → 验收）
#
# 前提：/opt/threshcore/award-log.jar 与 /opt/threshcore/dist/ 已就位
#
# 用法（需 root）：
#   sudo DB_PASSWORD=xxx AI_API_KEY=sk-xxx bash scripts/deploy/install_jar_dist.sh
#   sudo bash scripts/deploy/install_jar_dist.sh   # 交互输入密码
#
# 可选环境变量：
#   INSTALL_ROOT=/opt/threshcore
#   DB_NAME=log_analysis
#   DB_USER=root
#   DB_PASSWORD=
#   AI_API_KEY=
#   QDRANT_URL=                 # Cloud 或 http://127.0.0.1:6333
#   QDRANT_API_KEY=             # Cloud 必填
#   QDRANT_COLLECTION=ops_knowledge
#   KNOWLEDGE_ENABLED=true
#   SKIP_NGINX=1          跳过 Nginx 配置
#   SKIP_DB_CHECK=1       跳过 MariaDB 连通检查
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/threshcore}"
ENV_FILE="/etc/threshcore/threshcore.env"
DB_NAME="${DB_NAME:-log_analysis}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"
AI_API_KEY="${AI_API_KEY:-}"
QDRANT_URL="${QDRANT_URL:-}"
QDRANT_API_KEY="${QDRANT_API_KEY:-}"
QDRANT_COLLECTION="${QDRANT_COLLECTION:-ops_knowledge}"
KNOWLEDGE_ENABLED="${KNOWLEDGE_ENABLED:-}"
SKIP_NGINX="${SKIP_NGINX:-0}"
SKIP_DB_CHECK="${SKIP_DB_CHECK:-0}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 root 或 sudo 执行本脚本" >&2
  exit 1
fi

echo "======== Jar + dist 部署 ========"
echo "安装目录: $INSTALL_ROOT"
echo ""

if [[ ! -f "$INSTALL_ROOT/award-log.jar" ]]; then
  echo "错误: 未找到 $INSTALL_ROOT/award-log.jar" >&2
  exit 1
fi
if [[ ! -f "$INSTALL_ROOT/dist/index.html" ]]; then
  echo "错误: 未找到 $INSTALL_ROOT/dist/index.html" >&2
  exit 1
fi

mkdir -p "$INSTALL_ROOT/logs" /etc/threshcore

if [[ -z "$DB_PASSWORD" ]]; then
  read -rsp "MariaDB 用户 ${DB_USER} 的密码: " DB_PASSWORD
  echo ""
fi
if [[ -z "$AI_API_KEY" ]]; then
  read -rsp "DeepSeek AI_API_KEY（可留空，回车跳过）: " AI_API_KEY
  echo ""
fi
if [[ -z "$QDRANT_URL" ]]; then
  read -rp "Qdrant URL（Cloud/本机；留空则关闭知识库）: " QDRANT_URL
  QDRANT_URL="${QDRANT_URL%%/}"
fi
if [[ -n "$QDRANT_URL" ]]; then
  KNOWLEDGE_ENABLED="${KNOWLEDGE_ENABLED:-true}"
  if [[ -z "$QDRANT_API_KEY" && "$QDRANT_URL" == *cloud.qdrant.io* ]]; then
    read -rsp "Qdrant API Key（Cloud）: " QDRANT_API_KEY
    echo ""
  fi
else
  KNOWLEDGE_ENABLED="${KNOWLEDGE_ENABLED:-false}"
fi

cat > "$ENV_FILE" <<EOF
SPRING_PROFILES_ACTIVE=prod-kylin

DB_DRIVER=org.mariadb.jdbc.Driver
DB_URL=jdbc:mariadb://127.0.0.1:3306/${DB_NAME}?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USERNAME=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}

AI_BASE_URL=https://api.deepseek.com
AI_API_KEY=${AI_API_KEY}
AI_CHAT_MODEL=deepseek-chat
AI_CHAT_TEMPERATURE=0.1
AI_CHAT_TOP_P=0.85
AI_EMBEDDING_MODEL=deepseek-chat

APP_MANAGEMENT_ENABLED=false
APP_AI_AUDIT_RELAXED_READ=false
# 浏览器 / Nginx 正式入口 Origin 白名单，多个来源用英文逗号分隔
APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://threshcore.example.com

AGENT_RUN_AS_USER=award-agent
AGENT_MIN_PRIVILEGE=true
AGENT_RUNTIME_ENABLED=true
AGENT_AUTONOMOUS_ENABLED=true
AGENT_AWM_ENABLED=true

OPS_DRY_RUN_GLOBAL=false
OPS_AUTO_REMEDIATION_ENABLED=true
OPS_AUTO_REMEDIATION_MODE=HYBRID
OPS_PATROL_ENABLED=true
OPS_PATROL_INSPECT_ROOTS=/var/log,/tmp,/var/tmp,/var/cache,${INSTALL_ROOT}/logs

RF_FORCE_HEURISTIC_LOONGARCH=true
LOG_COLLECTOR_ROOT=/var/log

KNOWLEDGE_ENABLED=${KNOWLEDGE_ENABLED}
KNOWLEDGE_SEED_ON_STARTUP=true
KNOWLEDGE_LOCAL_EMBED_FALLBACK=true
QDRANT_URL=${QDRANT_URL}
QDRANT_API_KEY=${QDRANT_API_KEY}
QDRANT_COLLECTION=${QDRANT_COLLECTION}

AWARD_MIDDLEWARE_KAFKA=false
AWARD_MIDDLEWARE_REDIS=false
ELASTIC_ENABLED=false
SERVER_TOMCAT_CONNECTION_TIMEOUT_MS=300000
EOF
chmod 600 "$ENV_FILE"
echo "[OK] 已写入 $ENV_FILE（知识库=${KNOWLEDGE_ENABLED} Qdrant=${QDRANT_URL:-未配置}）"

if [[ "$SKIP_DB_CHECK" != "1" ]]; then
  DB_CLI=""
  if command -v mariadb >/dev/null 2>&1; then
    DB_CLI=mariadb
  elif command -v mysql >/dev/null 2>&1; then
    DB_CLI=mysql
  fi
  if [[ -n "$DB_CLI" ]]; then
    systemctl enable --now mariadb 2>/dev/null || systemctl enable --now mysql 2>/dev/null || systemctl enable --now mysqld 2>/dev/null || true
    if ! "$DB_CLI" -u "$DB_USER" -p"$DB_PASSWORD" -e "USE ${DB_NAME}; SELECT 1;" >/dev/null 2>&1; then
      echo "[警告] 无法连接 MariaDB (${DB_USER}@${DB_NAME})，请确认库已创建且 schema.sql 已导入" >&2
    else
      echo "[OK] MariaDB 连通正常"
    fi
  else
    echo "[警告] 未找到 mariadb/mysql 客户端，跳过数据库检查"
  fi
fi

SERVICE_SRC="$ROOT/scripts/deploy/award-log.service"
if [[ ! -f "$SERVICE_SRC" ]]; then
  SERVICE_SRC="/dev/stdin"
  cat > /etc/systemd/system/award-log.service <<'UNIT'
[Unit]
Description=ThreshCore award-log
After=network.target mariadb.service
Wants=mariadb.service

[Service]
Type=simple
User=root
WorkingDirectory=/opt/threshcore
EnvironmentFile=-/etc/threshcore/threshcore.env
ExecStart=/usr/bin/java -Xms256m -Xmx1024m -XX:MaxMetaspaceSize=256m -jar /opt/threshcore/award-log.jar
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=award-log

[Install]
WantedBy=multi-user.target
UNIT
else
  cp -f "$SERVICE_SRC" /etc/systemd/system/award-log.service
fi
echo "[OK] systemd 单元已安装"

echo "[..] 停止占用 8088 的旧 Java 进程..."
systemctl stop award-log 2>/dev/null || true
if command -v ss >/dev/null 2>&1; then
  OLD_PIDS=$(ss -tlnp 2>/dev/null | grep ':8088' | grep -oP 'pid=\K[0-9]+' || true)
  for pid in $OLD_PIDS; do
    kill "$pid" 2>/dev/null || kill -9 "$pid" 2>/dev/null || true
  done
fi
pkill -f "award-log.jar" 2>/dev/null || true
sleep 1

systemctl daemon-reload
systemctl enable award-log
systemctl restart award-log
echo "[OK] award-log 已启动"

sleep 3
if curl -sf "http://127.0.0.1:8088/award-log/actuator/health" >/dev/null; then
  echo "[OK] 健康检查: UP"
else
  echo "[警告] 健康检查未通过，查看日志: journalctl -u award-log -n 80 --no-pager" >&2
fi

if [[ "$SKIP_NGINX" != "1" ]] && command -v nginx >/dev/null 2>&1; then
  NGINX_CONF="/etc/nginx/conf.d/threshcore.conf"
  cat > "$NGINX_CONF" <<'NGINX'
server {
    listen 80;
    server_name _;

    root /opt/threshcore/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /award-log/ {
        proxy_pass http://127.0.0.1:8088/award-log/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
    }

    location /award-log/ws/ {
        proxy_pass http://127.0.0.1:8088/award-log/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
    }
}
NGINX
  nginx -t
  systemctl reload nginx 2>/dev/null || systemctl restart nginx
  echo "[OK] Nginx 已配置，浏览器访问 http://<本机IP>/"
elif [[ "$SKIP_NGINX" != "1" ]]; then
  echo "[提示] 未安装 nginx，跳过。后端直连: http://127.0.0.1:8088/award-log/"
fi

echo ""
echo "======== 完成 ========"
echo "  环境变量: $ENV_FILE"
echo "  服务状态: systemctl status award-log"
echo "  日志:     journalctl -u award-log -f"
echo "  验收:     curl http://127.0.0.1:8088/award-log/api/platform/acceptance"
echo ""
