# 从 GitHub origin/main 恢复 PPT 制作详案
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Set-Location -LiteralPath $root

Write-Host ">>> git fetch origin main ..."
git fetch origin main
if ($LASTEXITCODE -ne 0) { git fetch origin }

$src = "docs/deployment/PPT制作详案-21页版.md"
Write-Host ">>> git checkout origin/main -- $src"
git checkout origin/main -- $src

$dst = "docs/deployment/PPT制作详案-23页路演定稿.md"
Copy-Item -LiteralPath $src -Destination $dst -Force

$lines = (Get-Content -LiteralPath $src).Count
Write-Host "OK: restored $src ($lines lines)"
Write-Host "     copied to $dst"
Write-Host "Remote: https://github.com/zhaokai0604/-agent"
