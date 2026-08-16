#!/usr/bin/env bash
# 麒麟 V11 + LoongArch64 目标机一次性环境准备（MariaDB / 运维用户 / 目录）
#
# 用法（仓库根目录，需 root 或 sudo）：
#   sudo bash scripts/deploy/setup_kylin_host.sh
#
# 环境变量（可选）：
#   DB_NAME           默认 log_analysis
#   DB_USER           默认 root
#   DB_PASSWORD       数据库用户密码（未设则交互输入）
#   AGENT_USER        默认 award-agent
#   INSTALL_ROOT      默认 /opt/threshcore
#   SKIP_MARIADB      1 跳过库初始化（库已存在时）
#   SKIP_MYSQL        兼容旧变量名，效果同上
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

DB_NAME="${DB_NAME:-log_analysis}"
DB_USER="${DB_USER:-root}"
DB_PASSWORD="${DB_PASSWORD:-}"
AGENT_USER="${AGENT_USER:-award-agent}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/threshcore}"
SKIP_MARIADB="${SKIP_MARIADB:-${SKIP_MYSQL:-0}}"
SCHEMA="${ROOT}/src/main/resources/schema.sql"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 root 或 sudo 执行本脚本" >&2
  exit 1
fi

ARCH="$(uname -m)"
echo "======== 麒麟 V11 + LoongArch 环境准备 ========"
echo "架构: $ARCH"
if [[ -f /etc/os-release ]]; then
  # shellcheck disable=SC1091
  source /etc/os-release
  echo "系统: ${PRETTY_NAME:-unknown}"
fi
echo ""

case "$ARCH" in
  loongarch64|mips64) ;;
  *)
    echo "[警告] 当前架构为 $ARCH，赛题交付目标为 loongarch64" >&2
    ;;
esac

need_cmds=(java mvn node npm mariadb systemctl)
for c in "${need_cmds[@]}"; do
  if ! command -v "$c" >/dev/null 2>&1; then
    if [[ "$c" == "mariadb" ]] && command -v mysql >/dev/null 2>&1; then
      echo "[OK] mysql -> $(command -v mysql)（兼容 MariaDB 客户端）"
    else
      echo "[缺失] 未找到命令: $c（请先安装 JDK 17、Maven、Node、MariaDB）" >&2
    fi
  else
    echo "[OK] $c -> $(command -v "$c")"
  fi
done
echo ""

db_cli() {
  if command -v mariadb >/dev/null 2>&1; then
    mariadb "$@"
  else
    mysql "$@"
  fi
}

if [[ "$SKIP_MARIADB" != "1" ]]; then
  echo "[MariaDB] 启动服务并初始化库..."
  systemctl enable --now mariadb 2>/dev/null || systemctl enable --now mysql 2>/dev/null || systemctl enable --now mysqld 2>/dev/null || true
  if [[ -z "$DB_PASSWORD" ]]; then
    read -rsp "请输入数据库用户 ${DB_USER} 的密码: " DB_PASSWORD
    echo ""
  fi
  if [[ ! -f "$SCHEMA" ]]; then
    echo "错误: 未找到 $SCHEMA" >&2
    exit 1
  fi
  if [[ "$DB_USER" == "root" ]]; then
    db_cli -u root -p"$DB_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL
  else
    db_cli -u root -p"$DB_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL
  fi
  db_cli -u "$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" < "$SCHEMA"
  echo "[MariaDB] 库 ${DB_NAME} 与 schema 已就绪"
else
  echo "[MariaDB] 跳过（SKIP_MARIADB=1）"
fi

if ! id "$AGENT_USER" >/dev/null 2>&1; then
  useradd --system --home-dir "/home/${AGENT_USER}" --create-home --shell /sbin/nologin "$AGENT_USER"
  echo "[用户] 已创建系统用户 ${AGENT_USER}"
else
  echo "[用户] ${AGENT_USER} 已存在"
fi

mkdir -p "${INSTALL_ROOT}/logs" "${INSTALL_ROOT}/tmp"
chmod 755 "${INSTALL_ROOT}" "${INSTALL_ROOT}/logs"
chown "${AGENT_USER}:${AGENT_USER}" "${INSTALL_ROOT}/tmp" || true
chmod 1777 "${INSTALL_ROOT}/tmp"
echo "[目录] ${INSTALL_ROOT} 已创建"

if [[ ! -f /etc/sudoers.d/award-agent ]]; then
  cp -f "${ROOT}/scripts/deploy/sudoers.award-agent.example" /etc/sudoers.d/award-agent
  chmod 440 /etc/sudoers.d/award-agent
  if visudo -cf /etc/sudoers.d/award-agent >/dev/null 2>&1; then
    echo "[sudoers] 已安装 /etc/sudoers.d/award-agent"
  else
    echo "[sudoers] 语法校验失败，请手动 visudo 检查" >&2
    rm -f /etc/sudoers.d/award-agent
  fi
else
  echo "[sudoers] /etc/sudoers.d/award-agent 已存在，跳过"
fi

ENV_FILE="/etc/threshcore/threshcore.env"
if [[ ! -f "$ENV_FILE" ]]; then
  mkdir -p /etc/threshcore
  cp -f "${ROOT}/scripts/deploy/env.kylin.prod.example" "$ENV_FILE"
  if [[ -n "$DB_PASSWORD" ]]; then
    sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=${DB_PASSWORD}/" "$ENV_FILE"
    sed -i "s/^DB_USERNAME=.*/DB_USERNAME=${DB_USER}/" "$ENV_FILE"
  fi
  chmod 600 "$ENV_FILE"
  echo "[env] 已生成 $ENV_FILE（请编辑 AI_API_KEY 等）"
else
  echo "[env] $ENV_FILE 已存在，跳过"
fi

cat <<EOF

======== 下一步 ========
1. 编辑密钥:  sudo vi /etc/threshcore/threshcore.env
2. 构建产物:  bash scripts/deploy/deploy_kylin_loongarch.sh
3. 安装服务:
     sudo cp deploy/release-kylin/award-log.jar ${INSTALL_ROOT}/
     sudo cp -r deploy/release-kylin/dist ${INSTALL_ROOT}/
     sudo cp scripts/deploy/award-log.service /etc/systemd/system/
     sudo systemctl daemon-reload && sudo systemctl enable --now award-log
4. 验收:       BASE=http://127.0.0.1:8088/award-log bash scripts/deploy/smoke_kylin_acceptance.sh

EOF
