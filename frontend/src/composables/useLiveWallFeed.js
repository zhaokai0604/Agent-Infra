import { reactive, ref, computed, onMounted, onUnmounted } from 'vue'
import {
  buildManagementWsUrl,
  getPerformanceData,
  getPatrolAlertsRecent,
  getPatrolCorrelationLatest,
  getPatrolHistoryTrend,
  getPatrolRemediationPending,
  getOpsTraceRecent,
  getPlatformInfo
} from '../api/index.js'
import {
  canAttemptPerformanceWs,
  notePerformanceWsFailure,
  notePerformanceWsOpen,
  performanceWsCooldownMs
} from '../utils/performanceWsBackoff.js'

const METRIC_DEFS = [
  { key: 'cpuUsage', label: 'CPU', color: '#38bdf8' },
  { key: 'memoryUsage', label: '内存', color: '#a78bfa' },
  { key: 'diskUsage', label: '磁盘', color: '#fbbf24' },
  { key: 'networkUsage', label: '网络', color: '#34d399' }
]

const RING_CAP = 180
const INTERVAL_MS = 15000
const PATROL_POLL_MS = 60000
const SLOW_POLL_MS = 300000
const HTTP_SKIP_AFTER_WS_MS = 30000
const WS_RECONNECT_CAP = 3

const VITE_AWARD_LOG_WS_URL =
  typeof import.meta.env?.VITE_AWARD_LOG_WS_URL === 'string'
    ? import.meta.env.VITE_AWARD_LOG_WS_URL.trim()
    : ''

function performanceWsUrl () {
  if (VITE_AWARD_LOG_WS_URL) return VITE_AWARD_LOG_WS_URL
  return buildManagementWsUrl('/ws/performance')
}

function formatClockTime (d = new Date()) {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

function formatAxisLabel (d = new Date()) {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function isDocumentVisible () {
  return typeof document === 'undefined' || document.visibilityState !== 'hidden'
}

/**
 * 实时大屏数据：性能 WS + 环形缓冲、巡检、溯源、平台信息（无 mock）。
 */
export function useLiveWallFeed () {
  const metricsMap = reactive({
    cpuUsage: null,
    memoryUsage: null,
    diskUsage: null,
    networkUsage: null
  })

  const seriesRing = reactive({
    labels: [],
    cpu: [],
    memory: [],
    disk: [],
    network: []
  })

  const patrolAlerts = ref([])
  const patrolTrend = ref([])
  const correlation = ref({})
  const pendingRemediation = ref({ hasPending: false })
  const recentTraces = ref([])
  const platformInfo = ref(null)

  const lastUpdated = ref('')
  const dataSource = ref('')
  const wsConnected = ref(false)
  const loadError = ref('')

  const lastPerfWsAt = ref(0)
  let perfWs = null
  let wsReconnectTimer = null
  let wsReconnectAttempt = 0
  let perfWsUnmounting = false
  let tickTimer = null
  let patrolTimer = null
  let slowTimer = null

  const diskHotspots = computed(() => {
    const top = correlation.value?.diskHotspotsTop
    return Array.isArray(top) ? top : []
  })

  const envSummary = computed(() => {
    const p = platformInfo.value
    if (!p) return { label: '连接中…', dryRun: false, ai: false }
    const profiles = Array.isArray(p.activeProfiles) ? p.activeProfiles.join('+') : 'default'
    const os = p.platform?.osName || ''
    const dry = p.security?.globalDryRun === true
    let label = dry ? `${profiles} · 演练` : profiles
    if (os) label += ` · ${os}`
    return {
      label,
      dryRun: dry,
      ai: p.security?.aiConfigured === true,
      host: typeof window !== 'undefined' ? window.location.hostname : ''
    }
  })

  function pushSeriesPoint () {
    const now = new Date()
    const label = formatAxisLabel(now)
    seriesRing.labels.push(label)
    seriesRing.cpu.push(metricsMap.cpuUsage ?? null)
    seriesRing.memory.push(metricsMap.memoryUsage ?? null)
    seriesRing.disk.push(metricsMap.diskUsage ?? null)
    seriesRing.network.push(metricsMap.networkUsage ?? null)
    if (seriesRing.labels.length > RING_CAP) {
      seriesRing.labels.shift()
      seriesRing.cpu.shift()
      seriesRing.memory.shift()
      seriesRing.disk.shift()
      seriesRing.network.shift()
    }
    lastUpdated.value = formatClockTime(now)
  }

  function applyPerformancePayload (data) {
    if (data.cpuUsage != null) metricsMap.cpuUsage = Number(data.cpuUsage)
    if (data.memoryUsage != null) metricsMap.memoryUsage = Number(data.memoryUsage)
    if (data.diskUsage != null) metricsMap.diskUsage = Number(data.diskUsage)
    if (data.networkUsage != null) metricsMap.networkUsage = Number(data.networkUsage)
    if (data.timestamp) {
      const parts = String(data.timestamp).split(' ')
      lastUpdated.value = parts.length >= 2 ? parts[1] : data.timestamp
    } else {
      lastUpdated.value = formatClockTime()
    }
    dataSource.value = 'WebSocket /award-log/ws/performance'
    lastPerfWsAt.value = Date.now()
    loadError.value = ''
    pushSeriesPoint()
  }

  function applyPatrolWsEnvelope (data) {
    if (data?.correlation && typeof data.correlation === 'object') {
      correlation.value = { ...data.correlation }
    }
    const ts = data?.timestamp || ''
    const findings = Array.isArray(data?.findings) ? data.findings : []
    if (findings.length) {
      const rows = findings.map((f) => ({ ...f, timestamp: f.timestamp || ts }))
      patrolAlerts.value = [...rows, ...patrolAlerts.value].slice(0, 40)
    }
  }

  function applyRemediationPending (data) {
    pendingRemediation.value =
      data && typeof data === 'object' ? { ...data, hasPending: !!data.hasPending } : { hasPending: false }
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('ops-patrol-pending-change', {
        detail: { ...pendingRemediation.value }
      }))
    }
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
      applyPerformancePayload(data)
      return
    }
    if (ch === 'patrol_alert') {
      applyPatrolWsEnvelope(data)
      return
    }
    if (ch === 'patrol_remediation_pending') {
      applyRemediationPending(data)
      return
    }
    if (ch === 'patrol_remediation') {
      applyRemediationPending({ hasPending: false })
    }
  }

  function schedulePerfWsCooldownRetry () {
    if (wsReconnectTimer != null || perfWsUnmounting) return
    wsReconnectTimer = window.setTimeout(() => {
      wsReconnectTimer = null
      connectPerformanceWs()
    }, Math.max(1000, performanceWsCooldownMs() + 250))
  }

  function schedulePerfWsReconnect () {
    if (!canAttemptPerformanceWs()) {
      wsConnected.value = false
      dataSource.value = 'GET /admin/statistics/performance（WS 已降级）'
      loadError.value = '实时 WebSocket 暂不可用，已切换轮询'
      schedulePerfWsCooldownRetry()
      return
    }
    if (wsReconnectAttempt >= WS_RECONNECT_CAP) return
    wsReconnectAttempt += 1
    const delay = Math.min(30000, 2000 * 2 ** (wsReconnectAttempt - 1))
    wsReconnectTimer = window.setTimeout(() => {
      wsReconnectTimer = null
      connectPerformanceWs()
    }, delay)
  }

  function disconnectPerformanceWs (resetAttempt, forUnmount) {
    if (forUnmount) perfWsUnmounting = true
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
    wsConnected.value = false
    if (resetAttempt) wsReconnectAttempt = 0
  }

  function connectPerformanceWs () {
    if (perfWsUnmounting) return
    if (!canAttemptPerformanceWs()) {
      dataSource.value = 'GET /admin/statistics/performance（WS 已降级）'
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
      wsConnected.value = true
      loadError.value = ''
    }
    perfWs.onmessage = handlePerfWsMessage
    perfWs.onclose = () => {
      wsConnected.value = false
      if (!perfWsUnmounting) {
        const state = notePerformanceWsFailure()
        if (state.disabled) {
          dataSource.value = 'GET /admin/statistics/performance（WS 已降级）'
          loadError.value = '实时 WebSocket 暂不可用，已切换轮询'
          schedulePerfWsCooldownRetry()
          return
        }
        schedulePerfWsReconnect()
      }
    }
    perfWs.onerror = () => {
      wsConnected.value = false
    }
  }

  async function httpPerformanceFallback () {
    if (!isDocumentVisible()) return
    const wsFresh = lastPerfWsAt.value && Date.now() - lastPerfWsAt.value < HTTP_SKIP_AFTER_WS_MS
    if (wsFresh) return
    try {
      const data = await getPerformanceData({ silent: true })
      METRIC_DEFS.forEach(({ key }) => {
        metricsMap[key] = data[key] ?? null
      })
      dataSource.value = 'GET /admin/statistics/performance'
      loadError.value = ''
      pushSeriesPoint()
    } catch {
      loadError.value = '性能数据拉取失败'
    }
  }

  async function fetchPatrolRealtime () {
    if (!isDocumentVisible()) return
    try {
      const [alerts, corr, pending] = await Promise.all([
        getPatrolAlertsRecent(30, { silent: true }),
        getPatrolCorrelationLatest({ silent: true }),
        getPatrolRemediationPending({ silent: true })
      ])
      patrolAlerts.value = Array.isArray(alerts) ? alerts : []
      correlation.value = corr && typeof corr === 'object' ? { ...corr } : {}
      applyRemediationPending(pending)
    } catch {
      /* 淇濈暀涓婃鐪熷疄鏁版嵁 */
    }
  }

  async function fetchPatrolTrendOnly () {
    if (!isDocumentVisible()) return
    try {
      const trend = await getPatrolHistoryTrend(7, { silent: true })
      patrolTrend.value = Array.isArray(trend) ? trend : []
    } catch {
      /* 淇濈暀涓婃鐪熷疄鏁版嵁 */
    }
  }

  async function fetchPatrolBundle () {
    try {
      const [alerts, corr, trend, pending] = await Promise.all([
        getPatrolAlertsRecent(30, { silent: true }),
        getPatrolCorrelationLatest({ silent: true }),
        getPatrolHistoryTrend(7, { silent: true }),
        getPatrolRemediationPending({ silent: true })
      ])
      patrolAlerts.value = Array.isArray(alerts) ? alerts : []
      correlation.value = corr && typeof corr === 'object' ? { ...corr } : {}
      patrolTrend.value = Array.isArray(trend) ? trend : []
      applyRemediationPending(pending)
    } catch {
      /* 保留上次真实数据 */
    }
  }

  async function fetchTraces () {
    if (!isDocumentVisible()) return
    try {
      const rows = await getOpsTraceRecent(20, { silent: true })
      recentTraces.value = (Array.isArray(rows) ? rows : [])
        .filter((r) => r?.toolName && r.toolName !== 'NONE')
        .slice(0, 12)
    } catch {
      recentTraces.value = []
    }
  }

  async function fetchPlatform () {
    if (!isDocumentVisible()) return
    try {
      platformInfo.value = await getPlatformInfo({ silent: true })
    } catch {
      platformInfo.value = null
    }
  }

  async function refreshAll () {
    await Promise.all([
      httpPerformanceFallback(),
      fetchPatrolRealtime(),
      fetchPatrolTrendOnly(),
      fetchTraces(),
      fetchPlatform()
    ])
  }

  function start () {
    if (!isDocumentVisible() || tickTimer || patrolTimer || slowTimer) {
      return
    }
    connectPerformanceWs()
    httpPerformanceFallback()
    fetchPatrolRealtime()
    fetchPatrolTrendOnly()
    fetchTraces()
    fetchPlatform()
    tickTimer = window.setInterval(httpPerformanceFallback, INTERVAL_MS)
    patrolTimer = window.setInterval(fetchPatrolRealtime, PATROL_POLL_MS)
    slowTimer = window.setInterval(() => {
      fetchPatrolTrendOnly()
      fetchTraces()
      fetchPlatform()
    }, SLOW_POLL_MS)
  }

  function stop (forUnmount = true) {
    if (tickTimer) clearInterval(tickTimer)
    if (patrolTimer) clearInterval(patrolTimer)
    if (slowTimer) clearInterval(slowTimer)
    tickTimer = null
    patrolTimer = null
    slowTimer = null
    disconnectPerformanceWs(true, forUnmount)
  }

  function handleVisibilityChange () {
    if (isDocumentVisible()) {
      start()
      return
    }
    stop(false)
  }

  onMounted(() => {
    start()
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', handleVisibilityChange)
    }
  })
  onUnmounted(() => {
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
    stop(true)
  })

  return {
    METRIC_DEFS,
    metricsMap,
    seriesRing,
    patrolAlerts,
    patrolTrend,
    correlation,
    diskHotspots,
    pendingRemediation,
    recentTraces,
    platformInfo,
    envSummary,
    lastUpdated,
    dataSource,
    wsConnected,
    loadError,
    refreshAll
  }
}
