# 麒麟 Jar + Dist 部署速查（实战版）

> 银河麒麟 V11 + LoongArch · Fat Jar + Nginx · MariaDB · `prod-kylin`  
> 安装目录默认 `/opt/threshcore`

---

## 1. Windows 构建

```powershell
cd frontend
Remove-Item -Recurse -Force dist, node_modules\.vite -ErrorAction SilentlyContinue
npm run build
# 确认：dist\assets\index-*.js 为新 hash（含 api/index.js 修复后不应再请求 :8089）
```

产物：

- `frontend\dist\` → 传到麒麟
- `target\award-log-1.0-SNAPSHOT.jar` → 复制为 `/opt/threshcore/award-log.jar`（须在龙芯本机构建 Jar；dist 可在 Windows 构建）

---

## 2. 麒麟安装目录

```bash
sudo mkdir -p /opt/threshcore/logs
sudo cp award-log.jar /opt/threshcore/
sudo cp -r dist /opt/threshcore/          # 若文件夹名是 "dist "（有空格）：
sudo cp -r ~/下载/"dist "/opt/threshcore/dist
```

---

## 3. 环境变量 `/etc/threshcore/threshcore.env`

```bash
SPRING_PROFILES_ACTIVE=prod-kylin
APP_MANAGEMENT_ENABLED=false

DB_URL=jdbc:mariadb://127.0.0.1:3306/log_analysis?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai
DB_USERNAME=threshcore
DB_PASSWORD=<你的密码>

AI_API_KEY=
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
RF_FORCE_HEURISTIC_LOONGARCH=true
AWARD_MIDDLEWARE_KAFKA=false
AWARD_MIDDLEWARE_REDIS=false
ELASTIC_ENABLED=false
```

```bash
sudo chmod 600 /etc/threshcore/threshcore.env
```

**关键：** `prod-kylin` 生产只开 **8088**，必须 `APP_MANAGEMENT_ENABLED=false`。若开双端口，登录 `/admin/*` 会 403 `MANAGEMENT_PORT_REQUIRED`。

---

## 4. Nginx（全部反代到 8088）

`/etc/nginx/conf.d/threshcore.conf`：

```nginx
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
        proxy_read_timeout 600s;
    }

    location /award-log/ws/ {
        proxy_pass http://127.0.0.1:8088/award-log/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

**不要**使用按路径分流 8089 的 `map` 配置（除非确认管理端口仍开启）。

---

## 5. 启动后端

```bash
sudo pkill -f award-log.jar
cd /opt/threshcore
export $(grep -v '^#' /etc/threshcore/threshcore.env | xargs)
java -Dapp.management.enabled=false -jar award-log.jar
```

看到 `STARTED SUCCESS` 后保持终端不关（或改用 systemd，见下）。

---

## 6. 验收

```bash
# 直连 Jar
curl http://127.0.0.1:8088/award-log/api/platform/acceptance

# 经 Nginx（用户实际访问方式）
curl http://127.0.0.1/award-log/api/platform/acceptance

# 登录（应非 MANAGEMENT_PORT_REQUIRED / 非 502）
curl -X POST http://127.0.0.1/award-log/admin/user/login \
  -H 'Content-Type: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' \
  -d '{"username":"test","password":"test"}'
```

浏览器：`http://<IP>/`（无痕或强刷）。Network 里 login 必须是 `http://<IP>/award-log/admin/user/login`，**不能带 :8089**。

---

## 7. systemd 可选（开机自启）

```ini
# /etc/systemd/system/award-log.service
[Unit]
Description=award-log
After=mariadb.service

[Service]
WorkingDirectory=/opt/threshcore
EnvironmentFile=/etc/threshcore/threshcore.env
Environment=APP_MANAGEMENT_ENABLED=false
ExecStart=/usr/bin/java -Dapp.management.enabled=false -jar /opt/threshcore/award-log.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now award-log
```

---

## 8. 常见问题

| 现象 | 原因 | 处理 |
|------|------|------|
| 页面开、API 连 `:8089` | 旧 dist | 重新 build，`index-*.js` hash 应变；`frontend/src/api/index.js` 已修复生产同域 |
| `ERR_CONNECTION_REFUSED :8089` | 8089 已关但前端仍连 | 换新版 dist |
| `502 Bad Gateway` | Jar 未起或 Nginx 指错端口 | 起 Jar；Nginx 只指 8088 |
| `MANAGEMENT_PORT_REQUIRED` | 双端口模式 + 请求打到 8088 | `APP_MANAGEMENT_ENABLED=false` 并 `-Dapp.management.enabled=false` |
| `cp dist` 失败 | 目录名 `dist ` 带空格 | `cp -r ~/下载/"dist "/opt/threshcore/dist` |
| 下载目录 | 中文系统 | `~/下载` 不是 `~/Downloads` |

---

## 9. 前端构建说明（已改代码）

`frontend/src/api/index.js`：生产环境经 Nginx 80 访问时，管理面 API 与业务面同走 `/award-log`，不再默认连 8089。

可选构建前写 `frontend/.env.production`（IP 换成实际值，两行相同）：

```bash
VITE_AWARD_LOG_BASE_URL=http://<目标机IP>/award-log
VITE_AWARD_LOG_MANAGEMENT_BASE_URL=http://<目标机IP>/award-log
```

---

## 10. 相关项目文件

| 文件 | 用途 |
|------|------|
| `scripts/deploy/env.kylin.prod.example` | 环境变量模板 |
| `scripts/deploy/nginx.kylin.sample.conf` | Nginx 样例 |
| `docs/deployment/麒麟V11-LoongArch生产上线检查清单.md` | 完整验收清单 |
| `docs/deployment/部署文档.md` | 官方部署文档 |

---

*文档版本：2026-06-22，来自麒麟实机 Jar+Dist 部署实践。*
