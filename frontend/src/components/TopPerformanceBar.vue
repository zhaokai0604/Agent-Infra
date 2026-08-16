<template>
  <div class="top-perf-bar" :class="{ 'top-perf-bar--vertical': layout === 'vertical' }">
    <div class="top-perf-title">
      <el-icon class="pulse"><Odometer /></el-icon>
      <span>本机负载</span>
      <el-tag v-if="envLabel" size="small" :type="envTagType" class="env-chip">{{ envLabel }}</el-tag>
    </div>
    <div
      v-for="m in metrics"
      :key="m.key"
      class="metric"
    >
      <el-tooltip placement="bottom" effect="dark" :show-after="200">
        <template #content>
          <div class="tip-line">{{ m.label }}</div>
          <div class="tip-line">当前 {{ formatPct(metricsMap[m.key]) }}</div>
          <div class="tip-line subtle">数据源：{{ perfDataSourceHint }}</div>
        </template>
        <div class="metric-inner">
          <span class="label">{{ m.short }}</span>
          <span class="val" :class="heatClass(metricsMap[m.key])">{{ formatPct(metricsMap[m.key]) }}</span>
          <el-progress
            :percentage="clampPct(metricsMap[m.key])"
            :stroke-width="5"
            :show-text="false"
            :color="progressColor(metricsMap[m.key])"
            class="mini-progress"
          />
        </div>
      </el-tooltip>
    </div>
    <span v-if="errorHint" class="err-hint" :title="errorHint">{{ errorHint }}</span>
    <span v-else-if="updatedAt" class="updated">{{ updatedAt }}</span>

    <div
      v-if="!lite && patrolTrendHasData"
      ref="patrolTrendRef"
      class="patrol-trend-chart"
      title="近7日巡检告警频次（按日）"
    />

    <el-button
      size="small"
      type="success"
      plain
      class="sec-check-btn"
      title="检测安全策略是否正常工作"
      @click="securityCheckVisible = true"
    >
      <el-icon><Lock /></el-icon>
      安全自检
    </el-button>
    <el-button
      size="small"
      type="primary"
      plain
      class="sec-check-btn"
      title="打开安全驾驶舱（策略回放 / 计划效果图）"
      @click="openSecurityCockpit"
    >
      驾驶舱
    </el-button>
    <SecuritySelfCheckDialog v-model="securityCheckVisible" />

    <el-popover
      v-if="!lite && showPatrolPopover"
      placement="bottom-end"
      :width="440"
      trigger="click"
      popper-class="patrol-popper-root"
    >
      <template #reference>
        <div class="patrol-hint clickable" :title="patrolHint">
          <el-badge :value="patrolCount" :hidden="patrolCount <= 0" class="patrol-badge">
            <el-tag size="small" type="warning" effect="dark">巡检</el-tag>
          </el-badge>
          <span class="patrol-text">{{ patrolHint }}</span>
        </div>
      </template>
      <div class="patrol-pop-inner">
        <el-alert
          v-if="pendingRemediation?.hasPending"
          type="warning"
          :closable="false"
          class="rem-pending-alert"
          title="待确认修复项（高风险操作需您确认）"
        >
          <div v-if="pendingRemediation.riskPatrolAutoMax != null" class="rem-threshold">
            自动执行阈值：风险分 &lt; {{ Number(pendingRemediation.riskPatrolAutoMax).toFixed(1) }} 的步骤已尝试自动执行（若有）。
          </div>
          <div class="rem-pend-body">{{ pendingRemediation.summary }}</div>
          <ul v-if="remediationStepLines.length" class="rem-steps">
            <li v-for="(ln, i) in remediationStepLines" :key="i">{{ ln }}</li>
          </ul>
          <el-button
            size="small"
            type="primary"
            class="rem-btn"
            :loading="remConfirmLoading"
            @click="onConfirmRemediation"
          >
            确认并执行
          </el-button>
        </el-alert>

        <template v-if="remediationCoverage.length">
          <el-divider content-position="left">处置方式</el-divider>
          <ul class="cov-list">
            <li v-for="(row, i) in remediationCoverage" :key="'cov-' + i" class="cov-li">
              <div class="cov-li-head">
                <el-tag size="small" :type="laneTagType(row.remediation && row.remediation.lane)">
                  {{ (row.remediation && row.remediation.lane) || '—' }}
                </el-tag>
                <span class="cov-title">{{ row.title || row.code }}</span>
              </div>
              <div class="cov-hint">{{ row.remediation && row.remediation.hint }}</div>
            </li>
          </ul>
        </template>

        <div class="patrol-pop-title">最近告警线索</div>
        <ul v-if="patrolList.length" class="patrol-list">
          <li v-for="(it, idx) in patrolList" :key="patrolItemKey(it, idx)" class="patrol-li">
            <div class="patrol-li-head">
              <el-tag size="small" :type="tagType(it.level)">{{ it.code || '—' }}</el-tag>
              <span class="patrol-li-title">{{ it.title }}</span>
            </div>
            <div class="patrol-li-detail">{{ it.detail }}</div>
            <div v-if="it.timestamp" class="patrol-li-ts">{{ it.timestamp }}</div>
          </li>
        </ul>
        <div v-else class="patrol-empty">暂无未过期的告警条目（仍可有下方关联快照）</div>

        <el-divider content-position="left">多维关联快照</el-divider>
        <div v-if="corrSummaryLines.length" class="corr-lines">
          <div v-for="(ln, i) in corrSummaryLines" :key="i" class="corr-line">{{ ln }}</div>
        </div>
        <div v-else class="patrol-empty">关联数据尚未采集</div>

        <el-divider content-position="left">近7日告警趋势</el-divider>
        <div ref="patrolTrendPopRef" class="patrol-trend-pop-chart" />

        <template v-if="hotspots.length">
          <el-divider content-position="left">目录热点 Top</el-divider>
          <ul class="hotspot-list">
            <li v-for="(h, i) in hotspots" :key="i">
              <code class="hot-path">{{ h.path }}</code>
              <span class="hot-mib">{{ formatMiB(h.approxMiB) }}</span>
            </li>
          </ul>
        </template>
      </div>
    </el-popover>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'

const props = defineProps({
  layout: {
    type: String,
    default: 'horizontal',
    validator: value => ['horizontal', 'vertical'].includes(value)
  },
  lite: {
    type: Boolean,
    default: false
  }
})

const layout = computed(() => props.layout)
const lite = computed(() => props.lite)
import { Odometer, Lock } from '@element-plus/icons-vue'
import SecuritySelfCheckDialog from './SecuritySelfCheckDialog.vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  buildManagementWsUrl,
  getPerformanceData,
  getPatrolAlertsRecent,
  getPatrolCorrelationLatest,
  getPatrolRemediationPending,
  confirmPatrolRemediation,
  getPatrolRemediationCoverage,
  getPatrolHistoryTrend,
  getPlatformInfo
} from '../api/index.js'
import {
  canAttemptPerformanceWs,
  notePerformanceWsFailure,
  notePerformanceWsOpen,
  performanceWsCooldownMs
} from '../utils/performanceWsBackoff.js'

/** 完整 ws/wss 地址时优先使用（前后端不同域部署时配置 .env） */
const VITE_AWARD_LOG_WS_URL = typeof import.meta.env?.VITE_AWARD_LOG_WS_URL === 'string'
  ? import.meta.env.VITE_AWARD_LOG_WS_URL.trim()
  : ''

const INTERVAL_MS = 15000
const PATROL_POLL_MS = 60000
const SLOW_PATROL_POLL_MS = 300000
const WS_RECONNECT_CAP = 3
/** WS 曾成功推送 performance 后，在此时间内跳过 HTTP 轮询（与后端 5s 推送对齐并留余量） */
const HTTP_SKIP_AFTER_WS_MS = 30000

const metrics = [
  { key: 'cpuUsage', label: 'CPU 使用率（采集周期内均值）', short: 'CPU' },
  { key: 'memoryUsage', label: '内存使用率', short: 'MEM' },
  { key: 'diskUsage', label: '工作盘使用率（后端进程所在盘）', short: '磁盘' },
  { key: 'networkUsage', label: '网络使用率（估算）', short: '网络' }
]

const metricsMap = reactive({
  cpuUsage: null,
  memoryUsage: null,
  diskUsage: null,
  networkUsage: null
})

const securityCheckVisible = ref(false)

function openSecurityCockpit() {
  window.dispatchEvent(new CustomEvent('ops-navigate-tab', { detail: { tab: 'security-cockpit' } }))
}

const updatedAt = ref('')
const errorHint = ref('')
const patrolHint = ref('')
const patrolCount = ref(0)
const patrolList = ref([])
const correlation = ref({})
const pendingRemediation = ref({ hasPending: false })
const remConfirmLoading = ref(false)
const remediationCoverage = ref([])
const patrolTrendRef = ref(null)
const patrolTrendPopRef = ref(null)
const patrolTrendHasData = ref(false)
const patrolTrendRows = ref([])
let patrolTrendChart = null
let patrolTrendPopChart = null
let timer = null
let patrolTimer = null
let slowPatrolTimer = null
let perfWs = null
let wsReconnectTimer = null
let wsReconnectAttempt = 0
let perfWsUnmounting = false
const lastPerfWsAt = ref(0)
const envLabel = ref('')
const envTagType = ref('info')

const perfDataSourceHint = computed(() => {
  if (lastPerfWsAt.value && Date.now() - lastPerfWsAt.value < HTTP_SKIP_AFTER_WS_MS) {
    return 'WebSocket /award-log/ws/performance（热数据）'
  }
  return 'GET /admin/statistics/performance（轮询兜底）'
})

function performanceWsUrl () {
  if (VITE_AWARD_LOG_WS_URL) {
    return VITE_AWARD_LOG_WS_URL
  }
  return buildManagementWsUrl('/ws/performance')
}

async function fetchRemediationCoverage () {
  try {
    const rows = await getPatrolRemediationCoverage({ silent: true })
    remediationCoverage.value = Array.isArray(rows) ? rows : []
  } catch {
    remediationCoverage.value = []
  }
}

function laneTagType (lane) {
  const u = String(lane || '').toUpperCase()
  if (u === 'AUTO') return 'success'
  if (u === 'CONFIRM') return 'danger'
  if (u === 'MIXED') return 'warning'
  if (u === 'MANUAL') return 'info'
  return 'info'
}

function applyRemediationPendingWs (data) {
  if (!data || typeof data !== 'object') {
    pendingRemediation.value = { hasPending: false }
    emitPatrolPendingChange(pendingRemediation.value)
    return
  }
  pendingRemediation.value = {
    hasPending: !!data.hasPending,
    proposalId: data.proposalId,
    summary: data.summary || '',
    steps: Array.isArray(data.steps) ? data.steps : [],
    expiresAtMs: data.expiresAtMs,
    riskPatrolAutoMax: data.riskPatrolAutoMax
  }
  emitPatrolPendingChange(pendingRemediation.value)
}

function emitPatrolPendingChange (pending) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent('ops-patrol-pending-change', {
    detail: pending && typeof pending === 'object' ? { ...pending } : { hasPending: false }
  }))
}

async function fetchRemediationPending () {
  try {
    const p = await getPatrolRemediationPending({ silent: true })
    applyRemediationPendingWs(p)
    refreshPatrolHint()
  } catch {
    pendingRemediation.value = { hasPending: false }
    refreshPatrolHint()
  }
}

async function onConfirmRemediation () {
  const id = pendingRemediation.value?.proposalId
  if (!id) {
    ElMessage.warning('无待确认方案')
    return
  }
  remConfirmLoading.value = true
  try {
    const res = await confirmPatrolRemediation(id, '确认执行')
    const actions = Array.isArray(res?.actions) ? res.actions : null
    if (actions && actions.length > 0) {
      const okCount = actions.filter((a) => a && a.success === true).length
      const failCount = actions.length - okCount
      if (failCount === 0) {
        ElMessage.success('已按方案执行修复')
        pendingRemediation.value = { hasPending: false }
        emitPatrolPendingChange(pendingRemediation.value)
        refreshPatrolHint()
      } else if (okCount === 0) {
        ElMessage.error((res && res.error) || res?.resultSummary || '修复全部失败')
        await fetchRemediationPending()
      } else {
        ElMessage.warning(
          (res && res.resultSummary) || `部分修复失败（成功 ${okCount} / 失败 ${failCount}）`
        )
        await fetchRemediationPending()
      }
      return
    }
    if (res && res.success) {
      ElMessage.success('已按方案执行修复')
      pendingRemediation.value = { hasPending: false }
      emitPatrolPendingChange(pendingRemediation.value)
      refreshPatrolHint()
    } else {
      ElMessage.error((res && res.error) || '执行失败')
      await fetchRemediationPending()
    }
  } catch (e) {
    ElMessage.error('请求失败')
    console.warn('[TopPerformanceBar] remediation confirm', e)
  } finally {
    remConfirmLoading.value = false
  }
}

function patrolItemKey (it, idx) {
  const id = it && (it.id ?? it.traceId ?? it.fingerprint)
  return id != null && String(id) !== '' ? String(id) : `idx-${idx}`
}

function refreshPatrolHint () {
  if (pendingRemediation.value?.hasPending) {
    const s = (pendingRemediation.value.summary || '').replace(/\s+/g, ' ')
    patrolHint.value = s
      ? `待确认修复：${s.slice(0, 96)}${s.length > 96 ? '…' : ''}`
      : '待确认自动修复 — 点开巡检确认'
    return
  }
  if (patrolList.value.length > 0) {
    const a = patrolList.value[0]
    const title = a.title || a.code || '告警'
    const detail = (a.detail || '').replace(/\s+/g, ' ')
    patrolHint.value = detail ? `${title} — ${detail.slice(0, 100)}${detail.length > 100 ? '…' : ''}` : title
  } else {
    const c = correlation.value
    if (c && c.timestamp) {
      patrolHint.value = `快照 CPU ${num(c.cpuUsagePct)}% 磁盘 ${num(c.diskUsagePct)}% · 点阅详情`
    } else {
      patrolHint.value = ''
    }
  }
}

function applyPatrolWsEnvelope (data) {
  if (data.correlation && typeof data.correlation === 'object') {
    correlation.value = { ...data.correlation }
  }
  const ts = data.timestamp || ''
  const findings = Array.isArray(data.findings) ? data.findings : []
  if (findings.length) {
    const rows = findings.map((f) => ({ ...f, timestamp: f.timestamp || ts }))
    patrolList.value = [...rows, ...patrolList.value].slice(0, 24)
    patrolCount.value = patrolList.value.length
  }
  refreshPatrolHint()
}

function applyPerformanceWsPayload (data) {
  if (data.cpuUsage != null) metricsMap.cpuUsage = data.cpuUsage
  if (data.memoryUsage != null) metricsMap.memoryUsage = data.memoryUsage
  if (data.diskUsage != null) metricsMap.diskUsage = data.diskUsage
  if (data.networkUsage != null) metricsMap.networkUsage = data.networkUsage
  if (data.timestamp) {
    const parts = String(data.timestamp).split(' ')
    updatedAt.value = parts.length >= 2 ? parts[1] : data.timestamp
  }
  errorHint.value = ''
  lastPerfWsAt.value = Date.now()
}

function handlePerfWsMessage (ev) {
  let data
  try {
    data = JSON.parse(ev.data)
  } catch {
    return
  }
  const ch = data.channel
  if (ch === 'performance') {
    applyPerformanceWsPayload(data)
    return
  }
  if (ch === 'patrol_alert') {
    applyPatrolWsEnvelope(data)
    return
  }
  if (ch === 'patrol_remediation_pending') {
    applyRemediationPendingWs(data)
    refreshPatrolHint()
    return
  }
  if (ch === 'patrol_remediation') {
    fetchRemediationPending()
    return
  }
}

function schedulePerfWsCooldownRetry () {
  if (wsReconnectTimer != null || perfWsUnmounting) {
    return
  }
  wsReconnectTimer = window.setTimeout(() => {
    wsReconnectTimer = null
    connectPerformanceWs()
  }, Math.max(1000, performanceWsCooldownMs() + 250))
}

function schedulePerfWsReconnect () {
  if (!canAttemptPerformanceWs()) {
    errorHint.value = '实时 WebSocket 暂不可用，已使用 HTTP 轮询'
    schedulePerfWsCooldownRetry()
    return
  }
  if (wsReconnectAttempt >= WS_RECONNECT_CAP) {
    return
  }
  wsReconnectAttempt += 1
  const delay = Math.min(30000, 2000 * 2 ** (wsReconnectAttempt - 1))
  wsReconnectTimer = window.setTimeout(() => {
    wsReconnectTimer = null
    connectPerformanceWs()
  }, delay)
}

function disconnectPerformanceWs (resetAttempt, forUnmount) {
  if (forUnmount) {
    perfWsUnmounting = true
  }
  if (wsReconnectTimer != null) {
    clearTimeout(wsReconnectTimer)
    wsReconnectTimer = null
  }
  if (perfWs) {
    perfWs.onopen = null
    perfWs.onmessage = null
    perfWs.onclose = null
    perfWs.onerror = null
    try {
      perfWs.close()
    } catch {
      /* noop */
    }
    perfWs = null
  }
  if (resetAttempt) {
    wsReconnectAttempt = 0
  }
}

function connectPerformanceWs () {
  if (typeof WebSocket === 'undefined') {
    return
  }
  perfWsUnmounting = false
  if (!canAttemptPerformanceWs()) {
    errorHint.value = '实时 WebSocket 暂不可用，已使用 HTTP 轮询'
    schedulePerfWsCooldownRetry()
    return
  }
  disconnectPerformanceWs(false, false)
  try {
    perfWs = new WebSocket(performanceWsUrl())
  } catch {
    notePerformanceWsFailure()
    schedulePerfWsReconnect()
    return
  }
  perfWs.onopen = () => {
    notePerformanceWsOpen()
    wsReconnectAttempt = 0
    errorHint.value = ''
    try {
      perfWs.send('sync')
    } catch {
      /* 首帧由服务端定时推送，refresh 为可选加速 */
    }
  }
  perfWs.onmessage = handlePerfWsMessage
  perfWs.onerror = () => {
    /* onclose 会负责重连 */
  }
  perfWs.onclose = () => {
    perfWs = null
    if (perfWsUnmounting) {
      return
    }
    const state = notePerformanceWsFailure()
    if (state.disabled) {
      errorHint.value = '实时 WebSocket 暂不可用，已使用 HTTP 轮询'
      schedulePerfWsCooldownRetry()
      return
    }
    schedulePerfWsReconnect()
  }
}

const hotspots = computed(() => {
  const top = correlation.value?.diskHotspotsTop
  return Array.isArray(top) ? top : []
})

const remediationStepLines = computed(() => {
  const steps = pendingRemediation.value?.steps
  if (!Array.isArray(steps) || !steps.length) return []
  return steps.map((st) => {
    const rs = st.riskScore != null && !Number.isNaN(Number(st.riskScore))
      ? ` 风险分 ${Number(st.riskScore).toFixed(1)}`
      : ''
    const k = st.kind || ''
    if (k === 'CLEAN_TEMP') return `临时清理 ${st.path || ''}${rs}`
    if (k === 'CLEAN_LOG') return `日志裁剪 ${st.path || ''}${rs}`
    if (k === 'RESTART_SERVICE') return `重启服务 ${st.serviceName || ''}${rs}`
    return `${k}${rs}`
  })
})

const showPatrolPopover = computed(() => {
  return !!(patrolHint.value || correlation.value?.timestamp || pendingRemediation.value?.hasPending)
})

const corrSummaryLines = computed(() => {
  const c = correlation.value || {}
  const lines = []
  if (c.timestamp) lines.push(`时间 ${c.timestamp}`)
  if (c.cpuUsagePct != null) lines.push(`CPU 使用率约 ${num(c.cpuUsagePct)}%`)
  if (c.memoryUsagePct != null) lines.push(`内存使用率约 ${num(c.memoryUsagePct)}%`)
  if (c.diskUsagePct != null) lines.push(`磁盘使用率约 ${num(c.diskUsagePct)}%`)
  if (c.alarmTotal24h != null) lines.push(`告警总数(24h 窗) ${c.alarmTotal24h}（ERROR≈${c.alarmErrorApprox ?? 0} FATAL≈${c.alarmFatalApprox ?? 0}）`)
  if (c.anomalyLogsDay1 != null && c.anomalyLogsDay1 !== -1) lines.push(`异常日志(近 1 天统计) ${c.anomalyLogsDay1}`)
  if (c.zombieProcesses != null) lines.push(`僵尸进程 ${c.zombieProcesses}`)
  if (c.novelDrainTemplateKinds1h != null) lines.push(`近 1h 新日志模板 ${c.novelDrainTemplateKinds1h} 种`)
  return lines
})

function num (v) {
  const n = Number(v)
  return Number.isNaN(n) ? '—' : n.toFixed(1)
}

function formatMiB (v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  return `${n.toFixed(1)} MiB`
}

function tagType (level) {
  const s = String(level || '').toUpperCase()
  if (s.includes('WARN')) return 'warning'
  if (s.includes('INFO')) return 'info'
  return ''
}

function clampPct (v) {
  const n = Number(v)
  if (Number.isNaN(n)) return 0
  return Math.round(Math.min(100, Math.max(0, n)))
}

function formatPct (v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  return `${Number(v).toFixed(1)}%`
}

function heatClass (v) {
  const n = Number(v)
  if (Number.isNaN(n)) return ''
  if (n >= 85) return 'hot'
  if (n >= 60) return 'warm'
  return ''
}

function progressColor (v) {
  const n = Number(v)
  if (Number.isNaN(n)) return '#64748b'
  if (n >= 85) return '#f87171'
  if (n >= 60) return '#fbbf24'
  return '#34d399'
}

function buildPatrolTrendOption (rows, compact) {
  const list = Array.isArray(rows) ? rows : []
  const labels = list.map((r) => {
    const d = String(r.day || '')
    return d.length >= 10 ? d.slice(5) : d
  })
  const alertCounts = list.map((r) => Number(r.alertCount ?? 0))
  const runCounts = list.map((r) => Number(r.runCount ?? 0))
  return {
    animation: false,
    grid: compact
      ? { left: 0, right: 0, top: 2, bottom: 0 }
      : { left: 36, right: 12, top: 24, bottom: 28 },
    tooltip: {
      trigger: 'axis',
      formatter (params) {
        const p = params?.[0]
        if (!p) return ''
        const i = p.dataIndex
        return `${list[i]?.day || p.axisValue}<br/>告警 ${alertCounts[i] ?? 0} 条<br/>巡检 ${runCounts[i] ?? 0} 次`
      }
    },
    xAxis: {
      type: 'category',
      data: labels,
      show: !compact,
      axisLabel: { fontSize: 10, color: '#94a3b8' }
    },
    yAxis: {
      type: 'value',
      show: !compact,
      minInterval: 1,
      axisLabel: { fontSize: 10, color: '#94a3b8' },
      splitLine: { lineStyle: { color: 'rgba(148,163,184,0.15)' } }
    },
    series: [
      {
        name: '告警',
        type: compact ? 'line' : 'bar',
        smooth: true,
        symbol: compact ? 'none' : 'circle',
        symbolSize: 4,
        data: alertCounts,
        areaStyle: compact ? { color: 'rgba(245,158,11,0.25)' } : undefined,
        itemStyle: { color: '#f59e0b' },
        lineStyle: compact ? { width: 2, color: '#f59e0b' } : undefined
      }
    ]
  }
}

function renderPatrolTrendCharts () {
  const rows = patrolTrendRows.value
  patrolTrendHasData.value = rows.length > 0
  if (!patrolTrendHasData.value) return

  nextTick(() => {
    if (patrolTrendRef.value) {
      if (!patrolTrendChart) {
        patrolTrendChart = echarts.init(patrolTrendRef.value)
      }
      patrolTrendChart.setOption(buildPatrolTrendOption(rows, true), true)
      patrolTrendChart.resize()
    }
    if (patrolTrendPopRef.value) {
      if (!patrolTrendPopChart) {
        patrolTrendPopChart = echarts.init(patrolTrendPopRef.value)
      }
      patrolTrendPopChart.setOption(buildPatrolTrendOption(rows, false), true)
      patrolTrendPopChart.resize()
    }
  })
}

async function fetchPatrolHistoryTrend () {
  try {
    const rows = await getPatrolHistoryTrend(7, { silent: true })
    patrolTrendRows.value = Array.isArray(rows) ? rows : []
    renderPatrolTrendCharts()
  } catch {
    patrolTrendRows.value = []
    patrolTrendHasData.value = false
  }
}

function isDocumentVisible () {
  return typeof document === 'undefined' || document.visibilityState !== 'hidden'
}

watch(showPatrolPopover, (open) => {
  if (open) {
    nextTick(() => {
      patrolTrendPopChart?.resize()
      if (!patrolTrendPopChart && patrolTrendPopRef.value && patrolTrendRows.value.length) {
        patrolTrendPopChart = echarts.init(patrolTrendPopRef.value)
        patrolTrendPopChart.setOption(buildPatrolTrendOption(patrolTrendRows.value, false), true)
      }
    })
  }
})

async function fetchPatrolRealtime () {
  if (!isDocumentVisible()) {
    return
  }
  try {
    const [list, corr, pending] = await Promise.all([
      getPatrolAlertsRecent(12, { silent: true }),
      getPatrolCorrelationLatest({ silent: true }),
      getPatrolRemediationPending({ silent: true })
    ])
    applyRemediationPendingWs(pending)
    correlation.value = corr && typeof corr === 'object' ? { ...corr } : {}
    patrolList.value = Array.isArray(list) ? list : []
    patrolCount.value = patrolList.value.length
    refreshPatrolHint()
  } catch {
    /* 闈欓粯 */
  }
}

async function fetchPatrolAnalytics () {
  if (!isDocumentVisible()) {
    return
  }
  await Promise.all([
    fetchPatrolHistoryTrend(),
    fetchRemediationCoverage()
  ])
}

async function runLightPatrolTick (includeAnalytics = false) {
  await fetchPatrolRealtime()
  if (includeAnalytics) {
    await fetchPatrolAnalytics()
  }
}

async function patrolTick () {
  try {
    const [list, corr] = await Promise.all([
      getPatrolAlertsRecent(12, { silent: true }),
      getPatrolCorrelationLatest({ silent: true })
    ])
    await fetchPatrolHistoryTrend()
    await fetchRemediationPending()
    await fetchRemediationCoverage()
    correlation.value = corr && typeof corr === 'object' ? { ...corr } : {}
    patrolList.value = Array.isArray(list) ? list : []

    patrolCount.value = patrolList.value.length
    refreshPatrolHint()
  } catch {
    /* 静默 */
  }
}

async function tick () {
  if (!isDocumentVisible()) {
    return
  }
  const wsFresh = lastPerfWsAt.value && (Date.now() - lastPerfWsAt.value < HTTP_SKIP_AFTER_WS_MS)
  if (wsFresh) {
    return
  }
  try {
    const data = await getPerformanceData({ silent: true })
    errorHint.value = ''
    metrics.forEach(({ key }) => {
      metricsMap[key] = data[key] ?? null
    })
    const now = new Date()
    updatedAt.value = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`
  } catch (e) {
    errorHint.value = '性能拉取失败'
  }
}

async function loadPlatformEnv () {
  try {
    const info = await getPlatformInfo({ silent: true })
    const profiles = Array.isArray(info?.activeProfiles) ? info.activeProfiles.join('+') : 'default'
    const profileLabels = { dev: '开发环境', prod: '生产环境', test: '测试环境', 'test-kylin': '麒麟测试' }
    const profileText = profiles.split('+').map((p) => profileLabels[p] || p).join('+')
    const os = info?.platform?.osName || info?.platform?.os || ''
    const dry = info?.security?.globalDryRun === true
    envLabel.value = dry ? `${profileText} · 演练` : profileText
    envTagType.value = dry ? 'warning' : (info?.prodProfileActive ? 'success' : 'info')
    if (os && !dry) {
      envLabel.value = `${envLabel.value} · ${os}`
    }
  } catch {
    envLabel.value = ''
  }
}

function startFeed () {
  if (!isDocumentVisible() || timer || patrolTimer || slowPatrolTimer) {
    return
  }
  loadPlatformEnv()
  tick()
  if (props.lite) {
    timer = setInterval(tick, 60000)
    return
  }
  runLightPatrolTick(true)
  timer = setInterval(tick, INTERVAL_MS)
  patrolTimer = setInterval(fetchPatrolRealtime, PATROL_POLL_MS)
  slowPatrolTimer = setInterval(fetchPatrolAnalytics, SLOW_PATROL_POLL_MS)
  connectPerformanceWs()
}

function stopFeed (forUnmount = false) {
  if (timer) clearInterval(timer)
  if (patrolTimer) clearInterval(patrolTimer)
  if (slowPatrolTimer) clearInterval(slowPatrolTimer)
  timer = null
  patrolTimer = null
  slowPatrolTimer = null
  disconnectPerformanceWs(true, forUnmount)
}

function handleVisibilityChange () {
  if (isDocumentVisible()) {
    startFeed()
    return
  }
  stopFeed(false)
}

onMounted(() => {
  startFeed()
  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', handleVisibilityChange)
  }
})

onUnmounted(() => {
  if (typeof document !== 'undefined') {
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  }
  stopFeed(true)
  patrolTrendChart?.dispose()
  patrolTrendPopChart?.dispose()
  patrolTrendChart = null
  patrolTrendPopChart = null
})
</script>

<style scoped>
.top-perf-bar {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-width: 0;
  padding: 0 12px;
  margin: 0 8px;
  border-radius: var(--ops-radius-sm);
  border: 1px solid var(--ops-border);
  background: var(--ops-panel-soft);
}

.top-perf-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--ops-text-subtle);
  flex-shrink: 0;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-weight: 600;
}

.env-chip {
  text-transform: none;
  letter-spacing: 0;
  font-weight: 500;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pulse {
  color: var(--ops-primary);
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.75;
  }
  50% {
    opacity: 1;
  }
}

.metric {
  flex-shrink: 0;
  min-width: 72px;
  max-width: 100px;
}

.metric-inner {
  display: flex;
  flex-direction: column;
  gap: 2px;
  cursor: default;
}

.metric .label {
  font-size: 10px;
  color: var(--ops-text-subtle);
}

.metric .val {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--ops-text);
}

.metric .val.warm {
  color: #fcd34d;
}

.metric .val.hot {
  color: #fca5a5;
}

.mini-progress :deep(.el-progress-bar__outer) {
  background: #e2e8f0;
}

.err-hint {
  font-size: 11px;
  color: #f87171;
  flex-shrink: 0;
}

.updated {
  font-size: 10px;
  color: #64748b;
  flex-shrink: 0;
  margin-left: 4px;
  font-variant-numeric: tabular-nums;
}

.patrol-trend-chart {
  width: 132px;
  height: 34px;
  flex-shrink: 0;
  cursor: default;
}

.patrol-trend-pop-chart {
  width: 100%;
  height: 160px;
  margin-bottom: 4px;
}

.patrol-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 300px;
  min-width: 0;
  margin-left: 8px;
  flex-shrink: 1;
}

.patrol-hint.clickable {
  cursor: pointer;
  border-radius: 8px;
  padding: 2px 4px;
  margin: -2px -4px;
  transition: background 0.15s ease;
}

.patrol-hint.clickable:hover {
  background: rgba(15, 23, 42, 0.04);
}

.patrol-badge :deep(.el-badge__content) {
  font-size: 10px;
  padding: 0 5px;
  height: 16px;
  line-height: 16px;
}

.patrol-text {
  font-size: 11px;
  color: var(--ops-text-subtle);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tip-line {
  line-height: 1.4;
}

.tip-line.subtle {
  opacity: 0.75;
  font-size: 12px;
  margin-top: 4px;
}

.top-perf-bar--vertical {
  flex: none;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  padding: 12px;
  margin: 0;
  width: 100%;
  border: none;
  background: transparent;
}

.top-perf-bar--vertical .top-perf-title {
  text-transform: none;
  letter-spacing: 0;
  font-size: 12px;
}

.top-perf-bar--vertical .metric {
  min-width: 0;
  max-width: none;
  width: 100%;
}

.top-perf-bar--vertical .patrol-trend-chart {
  width: 100%;
  height: 72px;
  margin: 0;
}

.top-perf-bar--vertical .sec-check-btn {
  margin: 0;
  width: 100%;
}

.top-perf-bar--vertical .patrol-hint {
  width: 100%;
}

@media (max-width: 1200px) {
  .top-perf-bar:not(.top-perf-bar--vertical) {
    display: none;
  }
}
</style>

<style>
.patrol-popper-root {
  --el-popover-padding: 12px;
}
.patrol-pop-inner {
  max-height: 70vh;
  overflow: auto;
}
.rem-pending-alert {
  margin-bottom: 12px;
}
.rem-pending-alert :deep(.el-alert__title) {
  font-size: 13px;
}
.rem-pend-body {
  font-size: 11px;
  color: #cbd5e1;
  line-height: 1.45;
  margin-bottom: 10px;
}
.rem-threshold {
  font-size: 10px;
  color: #64748b;
  margin-bottom: 8px;
  line-height: 1.35;
}
.rem-btn {
  margin-top: 4px;
}
.rem-steps {
  list-style: none;
  margin: 0 0 10px;
  padding: 0;
  font-size: 10px;
  color: #94a3b8;
  line-height: 1.4;
}
.rem-steps li {
  padding: 2px 0;
}
.patrol-pop-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: #e2e8f0;
}
.patrol-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 240px;
  overflow: auto;
}
.patrol-li {
  padding: 8px 0;
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
}
.patrol-li:last-child {
  border-bottom: none;
}
.patrol-li-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.patrol-li-title {
  font-size: 12px;
  font-weight: 600;
  color: #f1f5f9;
}
.patrol-li-detail {
  font-size: 11px;
  color: #cbd5e1;
  margin-top: 4px;
  line-height: 1.45;
}
.patrol-li-ts {
  font-size: 10px;
  color: #64748b;
  margin-top: 4px;
}
.patrol-empty {
  font-size: 11px;
  color: #94a3b8;
}
.corr-lines {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.corr-line {
  font-size: 11px;
  color: #cbd5e1;
  font-variant-numeric: tabular-nums;
}
.hotspot-list {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 11px;
}
.hotspot-list li {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px solid rgba(51, 65, 85, 0.35);
}
.hot-path {
  color: #93c5fd;
  word-break: break-all;
  flex: 1;
  font-size: 10px;
}
.hot-mib {
  color: #fcd34d;
  flex-shrink: 0;
}
.sec-check-btn {
  margin-left: 6px;
  flex-shrink: 0;
}
.cov-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 200px;
  overflow: auto;
}
.cov-li {
  padding: 8px 0;
  border-bottom: 1px solid rgba(51, 65, 85, 0.35);
}
.cov-li:last-child {
  border-bottom: none;
}
.cov-li-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.cov-title {
  font-size: 11px;
  font-weight: 600;
  color: #e2e8f0;
}
.cov-hint {
  font-size: 10px;
  color: #94a3b8;
  margin-top: 4px;
  line-height: 1.4;
}
</style>
