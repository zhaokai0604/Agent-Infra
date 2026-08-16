/**
 * MCP 工具结果 → 工作台/工具台可读中文摘要（统一契约，避免各页面各写一套）。
 */
import { formatResultPreview } from './structuredDataView'

export const TOOLS_UNWRAP_INNER = new Set([
  'DiskTool',
  'DiskOpsTool',
  'DiskAnalyzeTool',
  'DiskInsightTool',
  'ProcessTool',
  'ProcessOpsTool',
  'SystemLoadTool',
  'LogAnalysisTool',
  'LogOpsTool',
  'CleanTempTool',
  'LogCleanupTool',
  'ServiceRestartTool',
  'ServiceOpsTool',
  'SystemdTool',
  'ConfigCheckTool',
  'ConfigDriftTool',
  'NetworkTool',
  'PortHealthTool',
  'SslCertTool',
  'PrivilegeTool',
  'DockerTool',
  'ContainerOpsTool',
  'OsInsightTool'
])

export function parseMaybeJsonString(val) {
  if (typeof val !== 'string') return val
  const t = val.trim()
  if (!t.startsWith('{') && !t.startsWith('[')) return val
  try {
    return JSON.parse(t)
  } catch {
    return val
  }
}

export function unwrapNestedToolPayload(val) {
  let current = parseMaybeJsonString(val)
  while (
    current &&
    typeof current === 'object' &&
    !Array.isArray(current) &&
    typeof current.success === 'boolean'
  ) {
    if (current.success !== true) {
      return {
        success: false,
        error: current.error || current.message || '子工具执行失败'
      }
    }
    if (current.data == null) break
    const next = parseMaybeJsonString(current.data)
    if (next === current.data) return next
    current = next
  }
  return current
}

export function formatBytesCompact(value) {
  if (value == null || value === '') return '-'
  if (typeof value === 'string' && /[a-zA-Z%]/.test(value)) return value
  const n = Number(value)
  if (!Number.isFinite(n)) return String(value)
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = Math.max(0, n)
  let idx = 0
  while (size >= 1024 && idx < units.length - 1) {
    size /= 1024
    idx += 1
  }
  const digits = size >= 100 || idx === 0 ? 0 : size >= 10 ? 1 : 2
  return `${size.toFixed(digits)} ${units[idx]}`
}

export function formatKbCompact(kb) {
  const n = Number(kb)
  if (!Number.isFinite(n)) return kb == null ? '-' : String(kb)
  return formatBytesCompact(n * 1024)
}

function parsePercentNumber(value) {
  const n = Number.parseFloat(String(value ?? '').replace('%', ''))
  return Number.isFinite(n) ? n : -1
}

export function formatDiskAnalyzeOverview(rawOverview) {
  const overview = unwrapNestedToolPayload(rawOverview)
  if (overview && typeof overview === 'object' && !Array.isArray(overview) && overview.success === false) {
    return `分区概况获取失败：${overview.error || '未知错误'}`
  }
  const rows = Array.isArray(overview) ? overview : []
  if (!rows.length) return '分区概况：未返回有效分区数据。'
  const sorted = [...rows].sort((a, b) => parsePercentNumber(b.usePercent) - parsePercentNumber(a.usePercent))
  return (
    '分区概况:\n' +
    sorted
      .slice(0, 6)
      .map((d) => {
        const mount = d.mountedOn || d.filesystem || d.path || '-'
        const used = formatBytesCompact(d.used)
        const size = formatBytesCompact(d.size)
        return `• ${mount}: 使用率 ${d.usePercent || '-'}，已用 ${used} / 共 ${size}`
      })
      .join('\n')
  )
}

export function formatDiskUsageSummary(rawData) {
  const unwrapped = unwrapNestedToolPayload(rawData)
  const diskArr = Array.isArray(rawData) ? rawData : Array.isArray(unwrapped) ? unwrapped : null
  if (!diskArr?.length) return ''
  const sorted = [...diskArr].sort((a, b) => parsePercentNumber(b.usePercent) - parsePercentNumber(a.usePercent))
  const highUsage = sorted.filter((d) => parsePercentNumber(d.usePercent) > 80)
  if (highUsage.length > 0) {
    return (
      `发现 ${highUsage.length} 个分区使用率超过 80%:\n` +
      highUsage
        .slice(0, 6)
        .map(
          (d) =>
            `• ${d.mountedOn || d.filesystem || '-'}: ${d.usePercent || '-'} (已用 ${formatBytesCompact(d.used)} / 共 ${formatBytesCompact(d.size)})`
        )
        .join('\n')
    )
  }
  const top = sorted[0]
  return `所有磁盘分区使用率正常，当前最高为 ${top?.usePercent || '-'}（${top?.mountedOn || top?.filesystem || '-'}）`
}

export function formatDiskHotspotsSummary(rawHotspots, title = '目录热点') {
  const hotspots = unwrapNestedToolPayload(rawHotspots)
  if (hotspots && typeof hotspots === 'object' && !Array.isArray(hotspots) && hotspots.success === false) {
    return `${title}获取失败：${hotspots.error || '未知错误'}`
  }
  if (!hotspots || typeof hotspots !== 'object' || Array.isArray(hotspots)) {
    return `${title}：未返回可解析结果。`
  }
  const entries = Array.isArray(hotspots.entries) ? hotspots.entries : []
  const root = hotspots.root || hotspots.rootPath || '-'
  if (!entries.length) {
    return `${title}（${root}）：未发现明显大目录。`
  }
  return (
    `${title}（${root}）:\n` +
    entries
      .slice(0, 10)
      .map((e, i) => `${i + 1}. ${formatKbCompact(e.kb)} - ${e.path || '-'}`)
      .join('\n')
  )
}

export function formatDiskAnalyzeHotspots(rawHotspots) {
  return formatDiskHotspotsSummary(rawHotspots, '目录热点')
}

export function formatProcessListSummary(list) {
  const unwrapped = unwrapNestedToolPayload(list)
  const arr = Array.isArray(list) ? list : Array.isArray(unwrapped) ? unwrapped : null
  if (!arr?.length) return ''
  const reasonText = (r) => {
    if (r === 'cpu') return '因CPU高入选'
    if (r === 'mem') return '因内存高入选'
    if (r === 'cpu+mem') return 'CPU与内存均高'
    return ''
  }
  return (
    `当前有 ${arr.length} 个进程超过阈值（CPU 或 物理内存占比）。\n` +
    `说明：内存%是该进程 Working Set 占整机物理内存的比例；系统总内存占用由大量进程分摊，不等于下列几行之和。\n` +
    arr
      .slice(0, 8)
      .map((p, i) => {
        const cpuText = p.cpu === 'n/a' || p.cpu == null ? 'CPU: 未采集' : `CPU: ${p.cpu}%`
        const memMb = p.memMb != null && p.memMb !== '' ? ` / ${p.memMb}MB` : ''
        const why = reasonText(p.reason)
        return `${i + 1}. ${p.command?.substring(0, 40) || 'unknown'} (PID: ${p.pid}, ${cpuText}, 内存: ${p.mem}%${memMb}${why ? `, ${why}` : ''})`
      })
      .join('\n')
  )
}

function formatPortHealthSummary(data) {
  if (!data || typeof data !== 'object') return ''
  const target = `${data.host || '-'}:${data.port || '-'}`
  if (data.reachable) {
    return `端口连通性检查 (${target}):\n• 状态: 可达\n• 建连耗时: ${data.connectMs ?? '-'} ms\n• 超时阈值: ${data.timeoutMs ?? '-'} ms`
  }
  return `端口连通性检查 (${target}):\n• 状态: 不可达\n• 建连耗时: ${data.connectMs ?? '-'} ms\n• 原因: ${data.error || '连接失败'}`
}

function formatSslCertSummary(data) {
  if (!data || typeof data !== 'object') return ''
  const status = data.expired ? '证书已过期' : data.expiringSoon ? '证书即将到期' : '证书有效'
  return (
    `TLS 证书检查 (${data.host || '-'}:${data.port || 443}):\n` +
    `• 状态: ${status}\n` +
    `• 剩余天数: ${data.daysUntilExpiry ?? '-'}\n` +
    `• 到期时间: ${data.notAfter || '-'}\n` +
    `• 签发者: ${data.issuer || '-'}`
  )
}

function formatConfigDriftSummary(data) {
  if (!data || typeof data !== 'object') return ''
  const status = data.firstObservation ? '已建立基线快照' : data.drifted ? '检测到配置漂移' : '与上次快照一致'
  return `配置漂移检查 (${data.configPath || '-'}):\n• 结论: ${status}\n• 信息: ${data.message || '-'}`
}

function parseServiceNamesFromOutput(output) {
  if (!output || typeof output !== 'string') return []
  const names = []
  const headerTokens = new Set(['name', 'status', 'starttype', 'unit', '----'])
  for (const line of output.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || /^-+$/.test(trimmed)) continue
    const token = trimmed.split(/\s+/)[0]?.toLowerCase()
    if (!token || headerTokens.has(token)) continue
    if (/^[a-z0-9@._-]{1,128}$/i.test(token)) names.push(token)
  }
  return names
}

function formatSystemdServiceSummary(data, title = '服务巡检结果') {
  if (!data || typeof data !== 'object') return ''
  const out = String(data.output || data.stderrOrOutput || '').trim()
  const names = parseServiceNamesFromOutput(out)
  const lines = [title]
  if (data.command) lines.push(`命令: ${data.command}`)
  if (!out) {
    lines.push('未发现应运行却未启动的服务（或本机无异常服务）。')
    return lines.join('\n')
  }
  if (names.length === 0) {
    const head = out.length > 800 ? out.slice(0, 800) + '\n...[truncated]' : out
    lines.push(head)
    return lines.join('\n')
  }
  const sample = names.slice(0, 8).join('、')
  const suffix = names.length > 8 ? ` 等 ${names.length} 项` : ''
  lines.push(`发现 ${names.length} 个异常服务：${sample}${suffix}`)
  return lines.join('\n')
}

function formatCommandOutputSummary(data, title) {
  if (!data || typeof data !== 'object') return ''
  const heading = title || data.tool || '命令结果'
  const summary = data.error || data.stderr || ''
  const out = (data.output || data.stderrOrOutput || '').trim()
  const head = out.length > 1200 ? out.slice(0, 1200) + '\n...[truncated]' : out
  const lines = [heading]
  if (data.command) lines.push(`命令: ${data.command}`)
  if (summary) lines.push(`结果: ${summary}`)
  if (head) lines.push(head)
  return lines.join('\n')
}

export function formatObjectAsNaturalLanguage(obj, depth = 0) {
  if (obj == null) return '（无返回内容）'
  if (typeof obj === 'string') {
    const t = obj.trim()
    if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
      try {
        return formatObjectAsNaturalLanguage(JSON.parse(t), depth)
      } catch {
        /* 非 JSON */
      }
    }
    return t.length > 3500 ? t.slice(0, 3500) + '\n…（输出已截断）' : t
  }
  if (typeof obj === 'number' || typeof obj === 'boolean') {
    return String(obj)
  }
  if (Array.isArray(obj)) {
    if (!obj.length) return '（空列表）'
    return obj
      .slice(0, 20)
      .map((item, i) => `${i + 1}. ${formatObjectAsNaturalLanguage(item, depth + 1)}`)
      .join('\n')
  }
  if (typeof obj === 'object') {
    const lines = []
    for (const [key, val] of Object.entries(obj)) {
      if (val == null || val === '') continue
      const label =
        {
          mode: '模式',
          path: '路径',
          plan: '将执行',
          note: '说明',
          filesFound: '发现文件数',
          filesDeleted: '已删除文件数',
          deletableCount: '可删除数',
          protectedSkipped: '已跳过受保护',
          previewCount: '预览条数',
          success: '是否成功',
          error: '错误',
          service: '服务',
          stderr: '错误输出',
          command: '命令',
          tool: '子工具',
          output: '输出',
          root: '扫描根',
          entries: '条目'
        }[key] || key
      if (key === 'preview' && Array.isArray(val)) {
        lines.push(`${label}（${val.length} 条）:\n` + val.slice(0, 8).map((p) => `  · ${p}`).join('\n'))
      } else if (key === 'protectedSamples' && Array.isArray(val)) {
        lines.push(`${label}: ${val.slice(0, 5).join('；')}`)
      } else if (key === 'entries' && Array.isArray(val)) {
        lines.push(
          val
            .slice(0, 12)
            .map((e, i) => {
              const kb = e.kb != null ? formatKbCompact(e.kb) : ''
              return `  ${i + 1}. ${kb} ${e.path || ''}`.trim()
            })
            .join('\n')
        )
      } else if (typeof val === 'object' && depth < 2) {
        lines.push(`${label}:\n${formatObjectAsNaturalLanguage(val, depth + 1)}`)
      } else {
        lines.push(`${label}: ${val}`)
      }
    }
    return lines.length ? lines.join('\n') : '（无有效字段）'
  }
  return String(obj)
}

export function generateHumanReadableResponse(toolName, result) {
  try {
    let data = parseMaybeJsonString(result)

    if (TOOLS_UNWRAP_INNER.has(toolName) && data && data.success === true && data.data != null) {
      const rawInner = data.data
      data = typeof rawInner === 'string' ? parseMaybeJsonString(rawInner) : rawInner
    }

    if (data && typeof data === 'object' && data.success === false) {
      const errMsg = data.error || data.message
      if (errMsg) {
        return `执行失败：${errMsg}`
      }
    }

    switch (toolName) {
      case 'DiskTool':
      case 'DiskOpsTool': {
        if (data?.overview != null || data?.hotspotsRank != null) {
          const parts = ['**磁盘综合诊断**']
          if (data?.overview != null) parts.push(formatDiskAnalyzeOverview(data.overview))
          if (data?.hotspotsRank != null) parts.push(formatDiskAnalyzeHotspots(data.hotspotsRank))
          if (data?.hint) parts.push(String(data.hint))
          return parts.join('\n\n')
        }
        if (Array.isArray(data)) {
          return formatDiskUsageSummary(data)
        }
        if (data?.entries) {
          return formatDiskHotspotsSummary(data, '磁盘热点')
        }
        if (data?.mode === 'DRY-RUN') {
          return (
            `⚠️ **预览模式（未实际删除）**\n` +
            `临时文件清理预览:\n• 路径: ${data.path}\n• 将删除 ${data.filesFound} 个文件\n• 预览前 ${data.previewCount} 个:\n` +
            (data.preview || []).map((f) => `  - ${f}`).join('\n')
          )
        }
        if (data?.mode === 'DELETE') {
          return (
            `✅ **已真实删除**\n` +
            `临时文件清理结果:\n• 路径: ${data.path}\n• 发现文件: ${data.filesFound}\n• 实际删除: ${data.filesDeleted} 个文件`
          )
        }
        if (data?.filesDeleted != null) {
          return `临时文件清理结果:\n• 路径: ${data.path}\n• 发现文件: ${data.filesFound}\n• 实际删除: ${data.filesDeleted} 个文件`
        }
        break
      }

      case 'ProcessTool':
      case 'ProcessOpsTool':
        if (Array.isArray(data)) {
          return formatProcessListSummary(data)
        }
        if (data?.mode === 'DRY-RUN') {
          return `结束进程预览:\n• PID: ${data.pid}\n• ${data.plan || ''}`
        }
        if (data?.pid != null && data?.command) {
          return `结束进程结果:\n• PID: ${data.pid}\n• 命令: ${data.command}\n• success=${data.success}\n• ${data.error || data.output || ''}`
        }
        break

      case 'SystemLoadTool': {
        if (data.cpuUsagePercent === undefined) break
        const badCpu = data.cpuUsagePercent < 0
        const badMem = data.memUsagePercent < 0
        const cpuLine = badCpu
          ? '• CPU 使用率: 未采集（常见于 Windows 或 top 输出格式与 Linux 不一致）'
          : `• CPU 使用率: ${data.cpuUsagePercent}%`
        const memLine = badMem ? '• 内存使用率: 未采集（同上）' : `• 内存使用率: ${data.memUsagePercent}%`
        return (
          `系统负载概况:\n${cpuLine}\n${memLine}\n` +
          `• 1分钟负载: ${data.loadAvg1min}\n` +
          `• 5分钟负载: ${data.loadAvg5min}\n` +
          `• 15分钟负载: ${data.loadAvg15min}`
        )
      }

      case 'LogAnalysisTool':
      case 'LogOpsTool':
        if (data.status) {
          const statusText = data.status === 'HEALTHY' ? '✅ 健康' : '⚠️ 存在异常'
          let response = `日志分析完成 (${data.logPath})\n状态: ${statusText}\n分析行数: ${data.linesAnalyzed}`
          if (data.errorCount > 0) {
            response += `\n检测到 ${data.errorCount} 条异常`
          }
          if (data.rootCause) {
            response += `\n\n根因分析:\n${data.rootCause}`
          }
          if (data.recommendation) {
            response += `\n\n修复建议:\n${data.recommendation}`
          }
          return response
        }
        if (data?.mode === 'DRY-RUN') {
          return (
            `日志清理预览:\n` +
            `• 路径: ${data.path}\n` +
            `• 候选文件: ${data.filesFound}\n` +
            `• 预览 ${data.previewCount ?? '-'} 条:\n` +
            (data.preview || []).map((f) => `  - ${f}`).join('\n')
          )
        }
        if (data?.filesDeleted != null) {
          return `日志清理执行:\n• 路径: ${data.path}\n• 候选: ${data.filesFound}\n• 已删: ${data.filesDeleted}`
        }
        break

      case 'CleanTempTool':
        if (data.mode === 'DRY-RUN') {
          return (
            `预览模式:\n` +
            `• 路径: ${data.path}\n` +
            `• 将删除 ${data.filesFound} 个文件\n` +
            `• 预览前 ${data.previewCount} 个:\n` +
            (data.preview || []).map((f) => `  - ${f}`).join('\n')
          )
        }
        return `清理完成:\n• 路径: ${data.path}\n• 发现文件: ${data.filesFound}\n• 实际删除: ${data.filesDeleted} 个文件`

      case 'LogCleanupTool':
        if (data.mode === 'DRY-RUN') {
          return (
            `日志清理预览:\n` +
            `• 路径: ${data.path}\n` +
            `• 候选文件: ${data.filesFound}\n` +
            `• 预览 ${data.previewCount ?? '-'} 条:\n` +
            (data.preview || []).map((f) => `  - ${f}`).join('\n')
          )
        }
        return `日志清理执行:\n• 路径: ${data.path}\n• 候选: ${data.filesFound}\n• 已删: ${data.filesDeleted}`

      case 'ServiceRestartTool':
      case 'ServiceOpsTool':
        if (data.mode === 'DRY-RUN') {
          return `服务重启预览:\n• ${data.service}\n• ${data.plan || ''}`
        }
        if (data?.service) {
          return `服务重启结果:\n• ${data.service}\n• success=${data.success}\n• ${data.stderr || ''}`
        }
        if (data?.tool || data?.output || data?.stderr) {
          return formatSystemdServiceSummary(data, '服务巡检结果')
        }
        break

      case 'DiskAnalyzeTool': {
        const d = parseMaybeJsonString(data)
        const parts = ['**磁盘综合诊断**']
        if (d?.overview != null) parts.push(formatDiskAnalyzeOverview(d.overview))
        if (d?.hotspotsRank != null) parts.push(formatDiskAnalyzeHotspots(d.hotspotsRank))
        if (Array.isArray(d?.multiDrive) && d.multiDrive.length) {
          parts.push(
            '多盘热点:\n' +
              d.multiDrive
                .slice(0, 6)
                .map((row) => {
                  const drive = row.drive || row.root || '-'
                  if (row.hotspotsError) return `• ${drive}: ${row.hotspotsError}`
                  return `• ${drive}: ${formatDiskAnalyzeHotspots(row.hotspots)}`
                })
                .join('\n')
          )
        }
        if (d?.hint) parts.push(String(d.hint))
        if (parts.length > 1) return parts.join('\n\n')
        break
      }

      case 'ConfigCheckTool': {
        const passed = data.passed ? '✅ 通过' : '❌ 失败'
        return (
          `配置文件检查 (${data.configType}):\n` +
          `• 路径: ${data.configPath}\n` +
          `• 结果: ${passed}\n` +
          `• 退出码: ${data.exitCode}\n` +
          (data.message ? `• 信息: ${data.message}` : '')
        )
      }

      case 'ConfigDriftTool':
        if (data?.configPath) {
          return formatConfigDriftSummary(data)
        }
        break

      case 'NetworkTool':
        if (data.type === 'ping') {
          return (
            `Ping 诊断 (${data.target}):\n` +
            `• 发送: ${data.transmitted} 个包\n` +
            `• 接收: ${data.received} 个包\n` +
            `• 丢包率: ${data.packetLossPercent}%\n` +
            `• 平均延迟: ${data.avgLatencyMs} ms\n` +
            `• 状态: ${data.success ? '✅ 网络正常' : '❌ 网络异常'}`
          )
        }
        if (data.type === 'traceroute') {
          return (
            `路由追踪 (${data.target}):\n` +
            `• 经过 ${data.hopCount} 跳\n` +
            (data.hops || []).map((h) => `  ${h.hop}. ${h.rtt1} | ${h.rtt2} | ${h.rtt3}`).join('\n')
          )
        }
        break

      case 'PortHealthTool':
        if (data?.host && data?.port != null) {
          return formatPortHealthSummary(data)
        }
        break

      case 'SslCertTool':
        if (data?.host) {
          return formatSslCertSummary(data)
        }
        break

      case 'PrivilegeTool': {
        const hasPriv = data.hasPrivilege ? '✅ 有权限' : data.needsSudo ? '⚠️ 需要 sudo' : '❌ 无权限'
        return (
          `权限检查 (${data.resource}):\n` +
          `• 当前用户: ${data.currentUser}\n` +
          `• 操作类型: ${data.action}\n` +
          `• 权限状态: ${hasPriv}\n` +
          (data.suggestion ? `• 建议: ${data.suggestion}` : '')
        )
      }

      case 'DiskInsightTool':
        return formatDiskHotspotsSummary(data, '磁盘热点')

      case 'SystemdTool':
        if (data?.tool || data?.output || data?.stderr) {
          return formatSystemdServiceSummary(data, '服务巡检结果')
        }
        break

      case 'DockerTool':
      case 'ContainerOpsTool':
        if (data?.mode === 'DRY-RUN') {
          return `容器操作预览:\n• 容器: ${data.containerName || '-'}\n• ${data.plan || ''}`
        }
        if (data?.tool || data?.output || data?.error) {
          return formatCommandOutputSummary(data, '容器巡检结果')
        }
        break

      case 'OsInsightTool': {
        let p = parseMaybeJsonString(result)
        if (p && p.success === true && p.data != null) {
          p = typeof p.data === 'string' ? parseMaybeJsonString(p.data) : p.data
        }
        if (typeof p === 'string') {
          p = parseMaybeJsonString(p)
        }
        if (!p || typeof p !== 'object') {
          return 'OS 感知：返回结构无法解析'
        }
        if (p.success === false) {
          return `OS 感知失败 (${p.tool || ''}): ${p.error || ''}`
        }
        const out = p.output != null ? String(p.output) : ''
        const head = out.length > 1600 ? out.slice(0, 1600) + '\n...[truncated]' : out
        return `OS 感知 [${p.tool || ''}]${p.command ? `\n命令: ${p.command}` : ''}\n${head || '(无输出)'}`
      }

      default:
        break
    }

    return formatObjectAsNaturalLanguage(data)
  } catch (e) {
    console.error('格式化 MCP 结果失败:', e)
  }
  return null
}

/** 工具台历史列表单行预览 */
export function formatMcpResultPreview(toolName, normalized, maxLen = 140) {
  if (!normalized) return '—'
  if (!normalized.success) {
    const err = normalized.error || normalized.data?.error || normalized.message || '执行失败'
    const line = `执行失败：${err}`
    return line.length > maxLen ? line.slice(0, maxLen) + '…' : line
  }
  const text = generateHumanReadableResponse(toolName, normalized.data)
  if (text) {
    const oneLine = text.replace(/\s+/g, ' ').trim()
    return oneLine.length > maxLen ? oneLine.slice(0, maxLen) + '…' : oneLine
  }
  return formatResultPreview(normalized.data != null ? normalized.data : normalized, maxLen)
}
