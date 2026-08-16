# ThreshCore / award-log 一键构建：前端 dist + 后端可执行 Jar，输出到 deploy/release/
#
# 用法（在仓库根目录，PowerShell）：
#   .\scripts\deploy\deploy.ps1
#   $env:SKIP_FRONTEND='1'; .\scripts\deploy\deploy.ps1   # 只打 Jar
#   $env:SKIP_BACKEND='1';  .\scripts\deploy\deploy.ps1   # 只打前端
#
# 环境变量：
#   DEPLOY_OUT   输出目录，默认 <项目根>\deploy\release
#   SKIP_TESTS   设为 0 时 Maven 会执行测试（默认跳过）
#
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $Root

$Out = if ($env:DEPLOY_OUT) { $env:DEPLOY_OUT } else { Join-Path $Root 'deploy\release' }
$SkipFront = $env:SKIP_FRONTEND -eq '1'
$SkipBack = $env:SKIP_BACKEND -eq '1'
$RunTests = $env:SKIP_TESTS -eq '0'

function Require-Cmd([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "未找到命令「$Name」，请先安装并加入 PATH。"
    }
}

Write-Host '======== ThreshCore 一键部署构建 ========'
Write-Host "项目根: $Root"
Write-Host "输出目录: $Out"
Write-Host ''

if (-not $SkipFront) {
    Require-Cmd 'node'
    Require-Cmd 'npm'
    Write-Host '[1/2] 构建前端 (frontend) ...'
    Push-Location (Join-Path $Root 'frontend')
    try {
        if (Test-Path 'package-lock.json') { npm ci } else { npm install }
        npm run build
    } finally {
        Pop-Location
    }
} else {
    Write-Host '[1/2] 跳过前端 (SKIP_FRONTEND=1)'
}

if (-not $SkipBack) {
    Require-Cmd 'mvn'
    Write-Host '[2/2] 构建后端 (Maven package) ...'
    if ($RunTests) {
        mvn -q package
    } else {
        mvn -q "-DskipTests" package
    }
} else {
    Write-Host '[2/2] 跳过后端 (SKIP_BACKEND=1)'
}

Write-Host ''
Write-Host "整理产物到: $Out"
if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Path (Join-Path $Out 'dist') -Force | Out-Null

if (-not $SkipFront) {
    Copy-Item -Path (Join-Path $Root 'frontend\dist\*') -Destination (Join-Path $Out 'dist') -Recurse -Force
}

if (-not $SkipBack) {
    $jars = Get-ChildItem -Path (Join-Path $Root 'target') -Filter 'award-log-*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' }
    $main = $jars | Select-Object -First 1
    if (-not $main) {
        throw '未在 target\ 下找到 award-log-*.jar，请确认 mvn package 已成功。'
    }
    Copy-Item -Path $main.FullName -Destination (Join-Path $Out 'award-log.jar') -Force
    Write-Host "已复制: $($main.Name) -> $(Join-Path $Out 'award-log.jar')"
}

Copy-Item -Path (Join-Path $PSScriptRoot 'nginx.sample.conf') -Destination (Join-Path $Out 'nginx.sample.conf') -Force

@'
ThreshCore (award-log) 部署说明
================================

1) 启动后端（需已配置 MariaDB 等，见 application.yml / 环境变量）

   java -jar award-log.jar

   默认端口 8088，上下文路径 /award-log
   生产建议: java -jar -Dspring.profiles.active=prod award-log.jar

2) 前端静态资源

   本目录 dist\ 可交给 Nginx root，并把浏览器对 /award-log 的请求反代到后端，示例见 nginx.sample.conf。

3) 前端 API 地址

   默认前端使用「当前域名 + 8088 端口」访问后端。若你用 Nginx 在 80 端口同域反代 /award-log，
   请同步修改 frontend\src\api\index.js 中的 baseURL，或让用户从 http://主机:8088 打开页面。

4) 数据与输出

   分析产物默认在进程工作目录下 target\output\<taskId>\，部署时注意磁盘与备份策略。
'@ | Set-Content -Path (Join-Path $Out 'README-DEPLOY.txt') -Encoding UTF8

Write-Host ''
Write-Host '完成。产物:'
Get-ChildItem $Out | Format-Table Name, Length -AutoSize
Write-Host '======== 结束 ========'
