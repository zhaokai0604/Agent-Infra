#!/usr/bin/env bash
# ========== ThreshCore 一键部署（含端口自动清理） ==========
# 用法：
#   1) 将 award-log-1.0-SNAPSHOT.jar 与 dist/ 放到 ~/下载
#   2) sudo bash scripts/deploy/deploy-from-downloads.sh
#      或：sudo SRC_DIR=/home/vmuser/下载 bash deploy-from-downloads.sh
#
# 非交互示例：
#   sudo DB_PASSWORD='xxx' AI_API_KEY='yyy' \
#        QDRANT_URL='https://xxx.cloud.qdrant.io' QDRANT_API_KEY='zzz' \
#        bash scripts/deploy/deploy-from-downloads.sh
#
set -euo pipefail

# ========== 配置变量 ==========
SRC_DIR="${SRC_DIR:-/home/vmuser/下载}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/threshcore}"
ENV_DIR="/etc/threshcore"
ENV_FILE="${ENV_DIR}/threshcore.env"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-log_analysis}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"
AI_API_KEY="${AI_API_KEY:-}"
APP_CONFIG_SECRET="${APP_CONFIG_SECRET:-}"
QDRANT_URL="${QDRANT_URL:-}"
QDRANT_API_KEY="${QDRANT_API_KEY:-}"
QDRANT_COLLECTION="${QDRANT_COLLECTION:-ops_knowledge}"
KNOWLEDGE_ENABLED="${KNOWLEDGE_ENABLED:-}"

# ========== 工具函数 ==========
pids_on_port() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -ti ":${port}" 2>/dev/null || true
    return
  fi
  # 麒麟常见：ss
  if command -v ss >/dev/null 2>&1; then
    ss -lptn "sport = :${port}" 2>/dev/null \
      | grep -oE 'pid=[0-9]+' \
      | cut -d= -f2 \
      | sort -u || true
    return
  fi
  # 兜底：fuser
  if command -v fuser >/dev/null 2>&1; then
    fuser -n "tcp/${port}" 2>/dev/null || true
  fi
}

echo "======== ThreshCore 麒麟生产部署 ========"
echo "源目录: ${SRC_DIR}"
echo "安装目录: ${INSTALL_ROOT}"
echo ""

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请用 sudo 执行：sudo bash $0"
  exit 1
fi

JAVA_BIN="$(command -v java || true)"
if [[ -z "${JAVA_BIN}" ]]; then
  echo "未找到 java，请先安装 JDK 17"
  exit 1
fi
echo "Java: ${JAVA_BIN}"

ARTIFACT="${SRC_DIR}/award-log-1.0-SNAPSHOT.jar"
if [[ ! -f "${ARTIFACT}" ]]; then
  # 兼容其它命名
  ARTIFACT="$(find "${SRC_DIR}" -maxdepth 1 -type f -name 'award-log-*.jar' | sort | tail -n 1 || true)"
fi
if [[ -z "${ARTIFACT}" || ! -f "${ARTIFACT}" ]]; then
  echo "未找到 ${SRC_DIR}/award-log-*.jar"
  exit 1
fi

if [[ ! -f "${SRC_DIR}/dist/index.html" ]]; then
  echo "${SRC_DIR}/dist/index.html 不存在，请确认 dist 文件夹完整"
  exit 1
fi

echo "后端包: ${ARTIFACT}"
echo "前端目录: ${SRC_DIR}/dist"
echo ""

# ---- 交互式输入 ----
if [[ -z "${DB_PASSWORD}" ]]; then
  read -rsp "MariaDB 密码（${DB_USER}@${DB_NAME}）: " DB_PASSWORD
  echo ""
fi
if [[ -z "${AI_API_KEY}" ]]; then
  read -rsp "DeepSeek API Key: " AI_API_KEY
  echo ""
fi
if [[ -z "${APP_CONFIG_SECRET}" ]]; then
  read -rsp "APP_CONFIG_SECRET（留空则自动生成）: " APP_CONFIG_SECRET
  echo ""
  if [[ -z "${APP_CONFIG_SECRET}" ]]; then
    APP_CONFIG_SECRET="$(openssl rand -hex 16 2>/dev/null || head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    echo "已自动生成 APP_CONFIG_SECRET"
  fi
fi

# ---- Qdrant ----
if [[ -z "${QDRANT_URL}" ]]; then
  echo ""
  echo "知识库向量库 Qdrant："
  echo "  - Cloud 示例: https://xxxx.us-east-2-0.aws.cloud.qdrant.io"
  echo "  - 本机 Docker: http://127.0.0.1:6333"
  echo "  - 不配知识库: 直接回车"
  read -rp "Qdrant URL: " QDRANT_URL
fi
QDRANT_URL="${QDRANT_URL%%/}"

if [[ -n "${QDRANT_URL}" ]]; then
  KNOWLEDGE_ENABLED="${KNOWLEDGE_ENABLED:-true}"
  if [[ -z "${QDRANT_API_KEY}" ]]; then
    if [[ "${QDRANT_URL}" == *cloud.qdrant.io* ]]; then
      read -rsp "Qdrant API Key（Cloud 必填）: " QDRANT_API_KEY
      echo ""
    else
      read -rsp "Qdrant API Key（本机可留空）: " QDRANT_API_KEY
      echo ""
    fi
  fi
else
  KNOWLEDGE_ENABLED="${KNOWLEDGE_ENABLED:-false}"
  echo "未配置 Qdrant：知识库将关闭（KNOWLEDGE_ENABLED=false）"
fi

# ========== 检查并清理 8088 端口 ==========
echo ""
echo "[0/10] 检查 8088 端口占用情况"

# 先停 systemd，再清端口，避免刚杀又被拉起
if systemctl list-unit-files 2>/dev/null | grep -q '^award-log.service'; then
  echo "发现 award-log.service，正在停止..."
  systemctl stop award-log 2>/dev/null || true
fi
pkill -f "award-log.*\\.jar" 2>/dev/null || true
sleep 1

PORT_PIDS="$(pids_on_port 8088)"
if [[ -n "${PORT_PIDS}" ]]; then
  echo "端口 8088 被占用，PID: ${PORT_PIDS}"
  for pid in ${PORT_PIDS}; do
    PORT_CMD="$(ps -p "${pid}" -o comm= 2>/dev/null || echo unknown)"
    echo "  结束进程 ${pid} (${PORT_CMD})"
    kill -9 "${pid}" 2>/dev/null || true
  done
  sleep 2
  if [[ -n "$(pids_on_port 8088)" ]]; then
    echo "端口 8088 仍被占用，请手动处理："
    echo "  ss -lptn 'sport = :8088'"
    echo "  kill -9 \$(ss -lptn 'sport = :8088' | grep -oE 'pid=[0-9]+' | cut -d= -f2)"
    exit 1
  fi
  echo "端口 8088 已释放"
else
  echo "端口 8088 未被占用"
fi

echo ""
echo "[1/10] 创建安装目录"
mkdir -p "${INSTALL_ROOT}/logs" "${ENV_DIR}"

echo "[2/10] 安装后端包"
rm -f "${INSTALL_ROOT}/award-log.jar"
cp -f "${ARTIFACT}" "${INSTALL_ROOT}/award-log.jar"
chmod 644 "${INSTALL_ROOT}/award-log.jar"
echo "后端包已安装"

echo "[3/10] 安装前端 dist"
rm -rf "${INSTALL_ROOT}/dist"
cp -r "${SRC_DIR}/dist" "${INSTALL_ROOT}/dist"
chmod -R 755 "${INSTALL_ROOT}/dist"
echo "前端已安装"

echo "[4/10] 写入生产环境变量"
cat > "${ENV_FILE}" <<ENV
SPRING_PROFILES_ACTIVE=prod-kylin
APP_MANAGEMENT_ENABLED=false
APP_CONFIG_SECRET=${APP_CONFIG_SECRET}
APP_AI_AUDIT_RELAXED_READ=false
# 浏览器 / Nginx 正式入口 Origin 白名单，多个来源用英文逗号分隔
APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://threshcore.example.com

DB_DRIVER=org.mariadb.jdbc.Driver
DB_URL=jdbc:mariadb://${DB_HOST}:${DB_PORT}/${DB_NAME}?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
DB_USERNAME=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}

AI_BASE_URL=https://api.deepseek.com
AI_API_KEY=${AI_API_KEY}
AI_CHAT_MODEL=deepseek-chat
AI_CHAT_TEMPERATURE=0.1
AI_CHAT_TOP_P=0.85
AI_EMBEDDING_MODEL=deepseek-chat

AGENT_RUNTIME_ENABLED=true
AGENT_AUTONOMOUS_ENABLED=true
AGENT_AWM_ENABLED=true
AGENT_AWM_SEED=true
AGENT_AWM_FAILURE_INSIGHT=true
AGENT_MIN_PRIVILEGE=true

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
ENV

chmod 600 "${ENV_FILE}"
echo "环境变量已写入 ${ENV_FILE}"

echo "[5/10] 检查 MariaDB 连接"
if command -v mariadb >/dev/null 2>&1; then
  if mariadb -u"${DB_USER}" -p"${DB_PASSWORD}" -e "USE ${DB_NAME}; SHOW TABLES;" >/dev/null 2>&1; then
    echo "MariaDB 连接正常"
  else
    echo "警告：无法连接或 USE ${DB_NAME} 失败。请先建库并导入 schema.sql"
  fi
else
  echo "未找到 mariadb 命令，跳过数据库连接检查"
fi

echo "[6/10] 探测 Qdrant"
if [[ -n "${QDRANT_URL}" ]]; then
  if [[ -n "${QDRANT_API_KEY}" ]]; then
    if curl -sf --connect-timeout 8 --max-time 20 \
        -H "api-key: ${QDRANT_API_KEY}" "${QDRANT_URL}/collections" >/dev/null 2>&1; then
      echo "Qdrant 可达: ${QDRANT_URL}"
    else
      echo "警告：暂无法访问 Qdrant ${QDRANT_URL}（网络/Key/防火墙）"
    fi
  else
    if curl -sf --connect-timeout 8 --max-time 20 "${QDRANT_URL}/collections" >/dev/null 2>&1; then
      echo "Qdrant 可达: ${QDRANT_URL}"
    else
      echo "警告：暂无法访问 Qdrant ${QDRANT_URL}"
    fi
  fi
else
  echo "跳过（未配置 QDRANT_URL）"
fi

echo "[7/10] 写入 systemd 服务"
cat > /etc/systemd/system/award-log.service <<UNIT
[Unit]
Description=ThreshCore award-log
After=network.target mariadb.service
Wants=mariadb.service

[Service]
Type=simple
User=root
WorkingDirectory=${INSTALL_ROOT}
EnvironmentFile=${ENV_FILE}
ExecStart=${JAVA_BIN} -Xms256m -Xmx1024m -XX:MaxMetaspaceSize=256m -Dapp.management.enabled=false -jar ${INSTALL_ROOT}/award-log.jar
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
LimitNOFILE=65535
StandardOutput=journal
StandardError=journal
SyslogIdentifier=award-log

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable award-log
echo "systemd 服务已配置"

echo "[8/10] 配置 Nginx"
if command -v nginx >/dev/null 2>&1; then
  mkdir -p /etc/nginx/conf.d
  cat > /etc/nginx/conf.d/threshcore.conf <<'NGINX'
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
  systemctl enable nginx 2>/dev/null || true
  systemctl reload nginx 2>/dev/null || systemctl restart nginx
  echo "Nginx 已配置并重载"
else
  echo "未安装 Nginx。后端仍会启动，可直连 http://127.0.0.1:8088/award-log/"
fi

echo "[9/10] 启动后端服务"
systemctl restart award-log
sleep 8

echo "[10/10] 验收"
echo ""
echo "======== 服务状态 ========"
systemctl status award-log --no-pager || true

echo ""
echo "======== 健康检查 ========"
if curl -sf http://127.0.0.1:8088/award-log/actuator/health >/dev/null 2>&1; then
  echo "后端健康检查通过 (8088)"
  curl -s http://127.0.0.1:8088/award-log/actuator/health | head -c 200
  echo ""
else
  echo "后端健康检查失败"
fi

if curl -sf http://127.0.0.1/award-log/actuator/health >/dev/null 2>&1; then
  echo "Nginx 代理健康检查通过 (80)"
else
  echo "Nginx 健康检查失败（未装 Nginx 可忽略）"
fi

echo ""
echo "======== 最近日志 ========"
journalctl -u award-log -n 30 --no-pager || true

echo ""
echo "======== 部署完成 ========"
echo "访问地址: http://服务器IP/"
echo "Qdrant: ${QDRANT_URL:-未配置} / 集合 ${QDRANT_COLLECTION}"
echo "实时日志: journalctl -u award-log -f"
echo "端口监听: ss -tlnp | grep 8088"
echo ""
echo "非交互部署示例："
echo "  sudo DB_PASSWORD='xxx' AI_API_KEY='yyy' \\"
echo "       QDRANT_URL='https://xxx.cloud.qdrant.io' QDRANT_API_KEY='zzz' \\"
echo "       bash $0"
