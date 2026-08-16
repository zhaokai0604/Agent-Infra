import { resolveOpsPaths } from './platformQuickPaths'

export { applyMcpWriteConfirmParams, userRequestedRealWrite } from './mcpWriteParams'

export const MCP_TOOL_COMMAND_HINT = {
  DiskTool: 'df -h',
  DiskInsightTool: 'du -xk --max-depth=N',
  DiskAnalyzeTool: 'df + du hotspot scan',
  ProcessTool: 'ps aux --sort=-%cpu',
  SystemLoadTool: 'top -bn1 + /proc/loadavg',
  LogAnalysisTool: 'log sample + Drain-Plus',
  CleanTempTool: 'find temp dir + optional rm',
  LogCleanupTool: 'find old logs + optional rm',
  ServiceRestartTool: 'systemctl restart',
  ConfigCheckTool: 'config syntax check',
  NetworkTool: 'ping / traceroute',
  PrivilegeTool: 'permission probe',
  OsInsightTool: 'journalctl | ss | lsof',
  PortHealthTool: 'tcp connect probe',
  DockerTool: 'docker ps / inspect',
  CronJobTool: 'crontab -l | schtasks',
  FirewallTool: 'ufw | firewalld | netsh',
  SslCertTool: 'TLS certificate inspect',
  SystemdTool: 'systemctl --failed | status',
  AutonomousOpsTool: 'patrol -> remediate -> pending/verify',
  ConfigDriftTool: 'config checksum drift check',
  DiskOpsTool: 'df | hotspots | clean-temp',
  LogOpsTool: 'analyze | cleanup',
  ServiceOpsTool: 'failed | status | restart',
  ContainerOpsTool: 'list | inspect | restart | stop',
  ProcessOpsTool: 'list | kill',
  RemoteDiskTool: 'ssh df -h',
  RemoteSystemLoadTool: 'ssh uptime/free',
  RemoteProcessTool: 'ssh ps aux',
  RemoteSystemdTool: 'ssh systemctl --failed',
  RemoteNetworkTool: 'ssh ping',
  RemoteLogAnalysisTool: 'ssh tail log',
  RemoteCleanTempTool: 'ssh find/rm temp',
  RemoteBatchInspectTool: 'ssh batch inspect'
}

/** 已注册 MCP 工具名（与后端 McpToolCatalog 对齐，用于从报告正文反查真实工具） */
export const MCP_TOOL_NAMES = Object.freeze(Object.keys(MCP_TOOL_COMMAND_HINT))

export function extractToolsUsedFromText(text) {
  if (!text || typeof text !== 'string') return []
  const found = []
  for (const name of MCP_TOOL_NAMES) {
    if (text.includes(`\`${name}\``) || text.includes(`${name} [`)) {
      found.push(name)
    }
  }
  return found
}

export function getMcpToolCommandHint(toolName, platformInfo = null) {
  const p = resolveOpsPaths(platformInfo)
  if (toolName === 'SystemdTool' || toolName === 'ServiceOpsTool') {
    return p.win ? 'Get-Service (Automatic+Stopped) | sc query' : 'systemctl --failed | status'
  }
  if (toolName === 'ServiceRestartTool') {
    return p.win ? 'Restart-Service / net start' : 'systemctl restart'
  }
  return MCP_TOOL_COMMAND_HINT[toolName] || 'system command'
}

const BADGE_MAP = {
  DiskTool: 'DSK',
  DiskInsightTool: 'DIR',
  DiskAnalyzeTool: 'DIA',
  ProcessTool: 'PRC',
  SystemLoadTool: 'LOD',
  LogAnalysisTool: 'LOG',
  CleanTempTool: 'TMP',
  LogCleanupTool: 'LGC',
  ServiceRestartTool: 'RST',
  ConfigCheckTool: 'CFG',
  NetworkTool: 'NET',
  PrivilegeTool: 'PRV',
  OsInsightTool: 'OSI',
  PortHealthTool: 'PRT',
  DockerTool: 'DKR',
  CronJobTool: 'CRN',
  FirewallTool: 'FWL',
  SslCertTool: 'SSL',
  SystemdTool: 'SVC',
  AutonomousOpsTool: 'AOP',
  ConfigDriftTool: 'DRF',
  DiskOpsTool: 'DOP',
  LogOpsTool: 'LOP',
  ServiceOpsTool: 'SOP',
  ContainerOpsTool: 'COP',
  ProcessOpsTool: 'POP',
  RemoteDiskTool: 'RDS',
  RemoteSystemLoadTool: 'RLD',
  RemoteProcessTool: 'RPC',
  RemoteSystemdTool: 'RSV',
  RemoteNetworkTool: 'RNT',
  RemoteLogAnalysisTool: 'RLG',
  RemoteCleanTempTool: 'RTM',
  RemoteBatchInspectTool: 'RBI'
}

export function mcpToolBadge(name) {
  if (BADGE_MAP[name]) return BADGE_MAP[name]
  return String(name || 'MCP').slice(0, 3).toUpperCase()
}

function param(name, label, placeholder, hint, type = 'text', options = null) {
  return { name, label, placeholder, hint, type, options }
}

function selectParam(name, label, defaultVal, options, hint) {
  return param(name, label, defaultVal, hint, 'select', options)
}

function numParam(name, label, defaultVal, hint) {
  return param(name, label, defaultVal, hint, 'number')
}

function boolParam(name, label, defaultVal, hint) {
  return param(name, label, defaultVal, hint, 'boolean')
}

const GATEWAY_PARAMS = {
  DiskOpsTool: {
    df: ['operation'],
    hotspots: ['operation', 'rootPath', 'maxDepth', 'topN'],
    analyze: ['operation', 'rootPath', 'topN', 'includeHotspots'],
    'clean-temp': ['operation', 'path', 'days', 'dryRun', 'confirmDelete']
  },
  LogOpsTool: {
    analyze: ['operation', 'logPath', 'lines'],
    cleanup: ['operation', 'path', 'days', 'dryRun', 'confirmDelete']
  },
  ServiceOpsTool: {
    failed: ['operation'],
    status: ['operation', 'serviceName'],
    restart: ['operation', 'serviceName', 'dryRun', 'confirmRestart']
  },
  SystemdTool: {
    failed: ['operation'],
    status: ['operation', 'serviceName'],
    restart: ['operation', 'serviceName', 'dryRun', 'confirmRestart']
  },
  ProcessOpsTool: {
    list: ['operation', 'minCpu', 'minMem'],
    kill: ['operation', 'pid', 'signal', 'dryRun', 'confirmKill']
  },
  ProcessTool: {
    list: ['operation', 'minCpu', 'minMem'],
    kill: ['operation', 'pid', 'signal', 'dryRun', 'confirmKill']
  },
  DockerTool: {
    list: ['operation', 'includeStopped'],
    inspect: ['operation', 'containerName'],
    restart: ['operation', 'containerName', 'dryRun', 'confirmRestart'],
    stop: ['operation', 'containerName', 'dryRun', 'confirmStop']
  },
  ContainerOpsTool: {
    list: ['operation', 'includeStopped'],
    inspect: ['operation', 'containerName'],
    restart: ['operation', 'containerName', 'dryRun', 'confirmRestart'],
    stop: ['operation', 'containerName', 'dryRun', 'confirmStop']
  },
  OsInsightTool: {
    journal: ['operation', 'sinceMinutes', 'maxLines'],
    ss: ['operation'],
    lsof: ['operation', 'pid']
  }
}

function buildToolDefinitions(platformInfo) {
  const p = resolveOpsPaths(platformInfo)
  const configPath = p.win ? 'C:/Windows/System32/drivers/etc/hosts' : '/etc/nginx/nginx.conf'
  return [
    { name: 'DiskTool', description: '磁盘分区使用率巡检', params: [] },
    { name: 'DiskInsightTool', description: '目录磁盘热点扫描', params: [
      param('rootPath', '目录', p.hotspotPath, '白名单路径'),
      param('maxDepth', '深度', '2', '可选'),
      param('topN', '热点数量', '10', '可选')
    ] },
    { name: 'DiskAnalyzeTool', description: '磁盘总览与热点联合分析', params: [
      param('rootPath', '目录', p.hotspotPath, '可选'),
      param('topN', '热点数量', '10', '可选'),
      param('includeHotspots', '含热点', 'true', 'true | false')
    ] },
    { name: 'ProcessTool', description: '进程资源占用查询或结束预览', params: [
      param('operation', '操作', 'list', 'list | kill'),
      param('pid', 'PID', '1234', 'kill 时必填'),
      param('signal', '信号', 'TERM', 'kill 时可选'),
      param('dryRun', '演练模式', 'true', 'kill 时建议开启'),
      param('confirmKill', '确认结束', 'false', '真实结束时使用')
    ] },
    { name: 'SystemLoadTool', description: '系统负载与资源概览', params: [] },
    { name: 'LogAnalysisTool', description: '日志采样与异常模式分析', params: [
      param('logPath', '日志路径', p.logPath, '可选'),
      param('lines', '分析行数', '100', '可选')
    ] },
    { name: 'CleanTempTool', description: '临时目录清理（写操作需确认）', params: [
      param('path', '路径', p.tempPath, '白名单目录'),
      param('days', '早于天数', '0', '0=含今日；子目录删除建议0'),
      param('dryRun', '演练模式', 'true', 'false 时需 confirmDelete=true'),
      param('confirmDelete', '确认删除', 'false', '真实删除时使用'),
      param('removeDirectory', '删除整个子目录', 'false', 'Temp 白名单子路径时可 true')
    ] },
    { name: 'LogCleanupTool', description: '日志目录旧文件清理（写操作需确认）', params: [
      param('path', '日志路径', p.logPath, '白名单目录'),
      param('days', '早于天数', '30', '正整数'),
      param('dryRun', '演练模式', 'true', 'false 时需 confirmDelete=true'),
      param('confirmDelete', '确认删除', 'false', '真实删除时使用')
    ] },
    { name: 'ServiceRestartTool', description: '白名单服务重启，默认预览', params: [
      param('serviceName', '服务单元', p.win ? 'W32Time' : 'nginx', '需在 allowlist 中'),
      param('dryRun', '演练模式', 'true', 'false 时需 confirmRestart=true'),
      param('confirmRestart', '确认重启', 'false', '真实重启时使用')
    ] },
    { name: 'ConfigCheckTool', description: '配置文件语法检查', params: [
      param('configPath', '配置文件', configPath, '必填')
    ] },
    { name: 'NetworkTool', description: '网络连通性诊断', params: [
      param('target', '目标', '127.0.0.1', '留空时 ping 本机'),
      param('type', '类型', 'ping', 'ping | traceroute'),
      param('count', '次数', '4', '可选')
    ] },
    { name: 'PrivilegeTool', description: '权限验证', params: [
      param('resource', '资源路径', p.win ? 'C:/Windows/System32/notepad.exe' : '/usr/bin/nginx', '必填'),
      param('action', '操作', 'execute', 'read/write/execute')
    ] },
    { name: 'OsInsightTool', description: 'journalctl / ss / lsof 查询', params: [
      param('operation', '操作', 'journal', 'journal | ss | lsof'),
      param('sinceMinutes', '最近分钟', '30', 'journal 可选'),
      param('maxLines', '最大行数', '200', 'journal 可选'),
      param('pid', 'PID', '4', 'lsof 可选')
    ] },
    { name: 'PortHealthTool', description: 'TCP 端口探测', params: [
      param('host', '主机', '127.0.0.1', '必填'),
      param('port', '端口', '8088', '1-65535'),
      param('timeoutMs', '超时(ms)', '3000', '可选')
    ] },
    { name: 'DockerTool', description: 'Docker 容器管理', params: [
      param('operation', '操作', 'list', 'list | inspect | restart | stop'),
      param('includeStopped', '包含已停止', 'false', 'list 可选'),
      param('containerName', '容器名', 'my-app', 'inspect/restart/stop'),
      param('dryRun', '演练模式', 'true', 'restart/stop 可选'),
      param('confirmRestart', '确认重启', 'false', 'restart 时使用'),
      param('confirmStop', '确认停止', 'false', 'stop 时使用')
    ] },
    { name: 'CronJobTool', description: '计划任务只读列表', params: [
      param('scope', '范围', 'user', 'user | system')
    ] },
    { name: 'FirewallTool', description: '防火墙状态检查', params: [] },
    { name: 'SslCertTool', description: 'TLS 证书有效期检查', params: [
      param('host', '主机', 'www.baidu.com', '必填'),
      param('port', '端口', '443', '默认 443'),
      param('timeoutMs', '超时(ms)', '5000', '可选')
    ] },
    { name: 'SystemdTool', description: p.win ? 'Windows/Linux 异常服务巡检' : 'systemd 服务状态或重启预览', params: [
      param('operation', '操作', 'failed', 'failed | status | restart'),
      param('serviceName', '服务名', p.win ? 'W32Time' : 'nginx', 'status/restart'),
      param('dryRun', '演练模式', 'true', 'restart 可选'),
      param('confirmRestart', '确认重启', 'false', 'restart 时使用')
    ] },
    { name: 'AutonomousOpsTool', description: '一键自主运维（采集→诊断→预览/执行→验证）', params: [
      param('userIntent', '意图', '自动运维', '可选'),
      param('forceRemediate', '强制修复', 'false', 'true 时允许策略内写操作')
    ] },
    { name: 'ConfigDriftTool', description: '配置漂移只读检查', params: [
      param('configPath', '配置文件', configPath, '白名单路径')
    ] },
    { name: 'DiskOpsTool', description: '磁盘组合工具', params: [
      selectParam('operation', '操作', 'df', ['df', 'hotspots', 'analyze', 'clean-temp'], 'df | hotspots | analyze | clean-temp'),
      param('rootPath', '扫描目录', p.hotspotPath, 'hotspots/analyze 时'),
      param('path', '清理路径', p.tempPath, 'clean-temp 时'),
      numParam('days', '早于天数', '0', 'clean-temp 时'),
      boolParam('dryRun', '演练模式', 'true', 'clean-temp 时使用'),
      boolParam('confirmDelete', '确认删除', 'false', '真实删除时使用')
    ] },
    { name: 'LogOpsTool', description: '日志组合工具', params: [
      selectParam('operation', '操作', 'analyze', ['analyze', 'cleanup'], 'analyze | cleanup'),
      param('logPath', '日志路径', p.logPath, 'analyze 时'),
      param('path', '清理路径', p.logPath, 'cleanup 时'),
      numParam('days', '早于天数', '30', 'cleanup 时'),
      boolParam('dryRun', '演练模式', 'true', 'cleanup 时使用'),
      boolParam('confirmDelete', '确认删除', 'false', '真实删除时使用')
    ] },
    { name: 'ServiceOpsTool', description: '服务组合工具', params: [
      param('operation', '操作', 'failed', 'failed | status | restart'),
      param('serviceName', '服务名', p.win ? 'W32Time' : 'nginx', 'status/restart'),
      param('dryRun', '演练模式', 'true', 'restart 时使用'),
      param('confirmRestart', '确认重启', 'false', '真实重启时使用')
    ] },
    { name: 'ContainerOpsTool', description: '容器组合工具', params: [
      param('operation', '操作', 'list', 'list | inspect | restart | stop'),
      param('containerName', '容器名', 'my-app', 'inspect/restart/stop'),
      param('dryRun', '演练模式', 'true', 'restart/stop 可选'),
      param('confirmRestart', '确认重启', 'false', 'restart 时使用'),
      param('confirmStop', '确认停止', 'false', 'stop 时使用')
    ] },
    { name: 'ProcessOpsTool', description: '进程组合工具', params: [
      param('operation', '操作', 'list', 'list | kill'),
      param('pid', 'PID', '1234', 'kill 时必填'),
      param('signal', '信号', 'TERM', 'kill 时可选'),
      param('dryRun', '演练模式', 'true', 'kill 时建议开启'),
      param('confirmKill', '确认结束', 'false', '真实结束时使用')
    ] }
  ]
}

/** @deprecated 使用 buildToolDefinitions(platformInfo) */
export const MCP_TOOL_DEFINITIONS = buildToolDefinitions(null)

export function mergeToolRegistry(serverResponse, options = {}) {
  const platformInfo = options.platformInfo ?? null
  const definitions = buildToolDefinitions(platformInfo)
  const serverList = Array.isArray(serverResponse?.data)
    ? serverResponse.data
    : Array.isArray(serverResponse)
      ? serverResponse
      : []

  for (const tool of serverList) {
    if (tool?.name && tool.commandHint) {
      MCP_TOOL_COMMAND_HINT[tool.name] = tool.commandHint
    }
  }

  const byServer = Object.fromEntries(serverList.filter(tool => tool?.name).map(tool => [tool.name, tool]))
  const known = new Set(definitions.map(tool => tool.name))

  const merged = definitions.map(definition => {
    const server = byServer[definition.name]
    const platformSupport = server?.platformSupport || null
    const available = server ? platformSupport?.available !== false : serverList.length === 0
    const status = !server && serverList.length > 0
      ? 'offline'
      : available
        ? 'online'
        : 'unavailable'
    const statusText = !server && serverList.length > 0
      ? '未在服务端注册'
      : available
        ? '在线'
        : (server?.unavailableReason || platformSupport?.reason || '当前平台不可用')
    return {
      ...definition,
      params: Array.isArray(server?.params) && server.params.length ? server.params : definition.params,
      displayName: server?.displayName || definition.description,
      summary: server?.summary || definition.summary || definition.description,
      description: server?.displayName || server?.description || definition.description,
      endpoint: server?.endpoint,
      status,
      statusText,
      group: server?.group || '',
      groupLabel: server?.groupLabel || '',
      operations: Array.isArray(server?.operations) ? server.operations : [],
      confirmStrategy: server?.confirmStrategy || '',
      dependencyChecks: Array.isArray(server?.dependencyChecks) ? server.dependencyChecks : [],
      defaultRiskScore: server?.defaultRiskScore,
      defaultParameters: server?.defaultParameters || {},
      platformSupport,
      warnings: Array.isArray(server?.warnings) ? server.warnings : []
    }
  })

  for (const tool of serverList) {
    if (!tool?.name || known.has(tool.name) || tool.name.startsWith('Remote')) continue
    merged.push({
      name: tool.name,
      displayName: tool.displayName || tool.description || tool.name,
      summary: tool.summary || tool.description || '服务端注册工具',
      description: tool.displayName || tool.description || tool.summary || tool.name,
      params: Array.isArray(tool.params) ? tool.params : [],
      endpoint: tool.endpoint,
      status: tool.platformSupport?.available === false ? 'unavailable' : 'online',
      statusText: tool.platformSupport?.available === false
        ? (tool.unavailableReason || tool.platformSupport?.reason || '当前平台不可用')
        : '在线',
      group: tool.group || '',
      groupLabel: tool.groupLabel || '',
      operations: Array.isArray(tool.operations) ? tool.operations : [],
      confirmStrategy: tool.confirmStrategy || '',
      dependencyChecks: Array.isArray(tool.dependencyChecks) ? tool.dependencyChecks : [],
      defaultRiskScore: tool.defaultRiskScore,
      defaultParameters: tool.defaultParameters || {},
      platformSupport: tool.platformSupport || null,
      warnings: Array.isArray(tool.warnings) ? tool.warnings : []
    })
  }

  return merged
}

/** 工具 Bean 名 → 界面展示标题 */
export function mcpToolDisplayName(toolName) {
  if (!toolName) return '运维工具'
  const def = buildToolDefinitions(null).find((t) => t.name === toolName)
  if (def?.description) return def.description
  return String(toolName)
}

/** 从工具元数据构建默认参数字典（用于立即执行） */
export function defaultParamsForTool(tool) {
  const raw = { ...((tool?.defaultParameters && typeof tool.defaultParameters === 'object') ? tool.defaultParameters : {}) }
  for (const p of tool?.params || []) {
    if ((raw[p.name] == null || raw[p.name] === '') && p?.placeholder != null && String(p.placeholder).trim() !== '') {
      raw[p.name] = p.placeholder
    }
  }
  const rules = GATEWAY_PARAMS[tool?.name]
  if (!rules) {
    return coerceToolParams(raw, tool)
  }
  const op = String(raw.operation || 'df').trim().toLowerCase()
  const allowed = rules[op]
  if (!allowed) {
    return coerceToolParams(raw, tool)
  }
  const filtered = {}
  for (const key of allowed) {
    if (raw[key] != null && String(raw[key]).trim() !== '') {
      filtered[key] = raw[key]
    } else if (key === 'operation') {
      filtered[key] = op
    }
  }
  return coerceToolParams(filtered, tool)
}

/** 表单字符串 → API 需要的 boolean/number */
export function coerceToolParams(params, tool) {
  const out = { ...(params || {}) }
  const meta = Object.fromEntries((tool?.params || []).map((p) => [p.name, p]))
  for (const [key, value] of Object.entries(out)) {
    const p = meta[key]
    if (!p) continue
    if (p.type === 'boolean') {
      out[key] = value === true || value === 'true' || value === '1'
    } else if (p.type === 'number') {
      const n = Number(value)
      if (!Number.isNaN(n)) out[key] = n
      else delete out[key]
    } else if (p.type === 'hostSelect') {
      const n = Number(value)
      if (!Number.isNaN(n) && n > 0) out[key] = n
      else delete out[key]
    } else if (value === '' || value == null) {
      delete out[key]
    }
  }
  return out
}
