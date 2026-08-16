# 安全样例：受控临时目录清理预览

本目录提供运行代码包所需的脱敏样例输入与输出。示例刻意使用 `dryRun: true`，不会执行真实删除；请将 `path` 替换为已在 `agent.paths` 白名单内的演示目录。

## 前置条件

1. 按根目录 README 配置并启动后端。
2. 创建一个只包含测试文件的演示目录，例如 `C:/agent-infra-demo/tmp`。
3. 不要把生产目录、真实日志、密钥或用户数据用于样例。

## 执行

```powershell
$body = Get-Content ./examples/clean-temp-dry-run.request.json -Raw
Invoke-RestMethod `
  http://localhost:8088/award-log/api/mcp/execute `
  -Method Post `
  -ContentType 'application/json' `
  -Body $body
```

## 预期

- 返回 HTTP `200`，且 `success` 为 `true`。
- `statusCode` 为 `200`，并返回可追溯的 `traceId`。
- 工具回执必须表明处于预览/Dry Run 状态，不产生真实删除。
- 若目录不在白名单，或当前平台不支持该工具，系统应返回拒绝/不可用结论和 `traceId`，而不是绕过策略执行。

`clean-temp-dry-run.response.example.json` 是脱敏的契约示例；实际字段中的时间、`traceId`、候选数量及具体路径会随运行环境变化。
