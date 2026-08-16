<template>
  <div class="ops-live-wall" :class="{ 'wall-fullscreen': isFullscreen }">
    <header class="wall-header">
      <div class="wall-brand">
        <span class="wall-title">资源总览</span>
        <span class="wall-sub">本机实时 · {{ envSummary.host || '—' }}</span>
      </div>
      <div class="wall-header-center">
        <span v-for="chip in headerChips" :key="chip.key" class="wall-chip" :class="chip.type">
          {{ chip.text }}
        </span>
      </div>
      <div class="wall-header-right">
        <span class="wall-clock">{{ clockText }}</span>
        <span class="wall-updated" :title="dataSource">
          {{ lastUpdated ? `更新 ${lastUpdated}` : '等待数据' }}
          <span v-if="loadError" class="wall-err"> · {{ loadError }}</span>
        </span>
        <el-button size="small" plain class="wall-btn" @click="refreshAll">刷新</el-button>
        <el-button size="small" type="primary" plain class="wall-btn" @click="toggleFullscreen">
          {{ isFullscreen ? '退出全屏' : '全屏' }}
        </el-button>
      </div>
    </header>

    <div v-if="pendingRemediation?.hasPending" class="wall-banner">
      <el-icon><Warning /></el-icon>
      <span>{{ pendingRemediation.summary || '有待确认的修复步骤' }}</span>
    </div>

    <div class="wall-body">
      <aside class="wall-gauges">
        <div v-for="m in METRIC_DEFS" :key="m.key" class="gauge-panel">
          <div class="gauge-label">{{ m.label }}</div>
          <div :ref="(el) => setGaugeRef(m.key, el)" class="gauge-chart" />
          <div class="gauge-val" :style="{ color: heatColor(metricsMap[m.key]) }">
            {{ formatPct(metricsMap[m.key]) }}
          </div>
        </div>
      </aside>

      <section class="wall-center">
        <div class="panel panel-trend">
          <div class="panel-head">
            <span>资源趋势</span>
            <span class="panel-meta">{{ seriesRing.labels.length ? `近 ${seriesRing.labels.length} 点` : '采集中' }}</span>
          </div>
          <div ref="trendRef" class="chart-box" />
          <div v-if="!seriesRing.labels.length" class="chart-empty">等待 WebSocket 推送本机性能数据…</div>
        </div>

        <div class="panel-row">
          <div class="panel panel-treemap">
            <div class="panel-head">
              <span>磁盘热点</span>
              <span class="panel-meta">按占用大小 · 悬停看完整路径</span>
            </div>
            <div ref="treemapRef" class="chart-box chart-box-sm" />
            <ul v-if="diskHotspots.length" class="hotspot-table">
              <li v-for="(h, i) in diskHotspots" :key="i" class="hotspot-row">
                <span class="hotspot-name" :title="h.path">{{ hotspotLabel(h) }}</span>
                <span class="hotspot-size">{{ formatHotspotSize(h.approxMiB) }}</span>
              </li>
            </ul>
            <div v-else class="chart-empty">暂无热点目录（等待巡检扫描）</div>
          </div>
          <div class="panel panel-patrol">
            <div class="panel-head">
              <span>近 7 日巡检告警</span>
            </div>
            <div ref="patrolRef" class="chart-box chart-box-sm" />
            <div v-if="!patrolTrend.length" class="chart-empty">暂无巡检趋势数据</div>
          </div>
        </div>
      </section>

      <aside class="wall-side">
        <div class="panel panel-alerts">
          <div class="panel-head">
            <span>实时告警</span>
            <span class="panel-meta">{{ patrolAlerts.length }} 条</span>
          </div>
          <ul v-if="patrolAlerts.length" class="alert-scroll">
            <li v-for="(a, i) in patrolAlerts" :key="alertKey(a, i)" class="alert-item">
              <el-tag size="small" :type="alertTagType(a.level)">{{ a.code || 'ALERT' }}</el-tag>
              <div class="alert-body">
                <div class="alert-title">{{ a.title || '告警' }}</div>
                <div class="alert-detail">{{ a.detail }}</div>
                <div v-if="a.timestamp" class="alert-ts">{{ a.timestamp }}</div>
              </div>
            </li>
          </ul>
          <div v-else class="side-empty">当前无活跃告警线索</div>
        </div>

        <div class="panel panel-traces">
          <div class="panel-head">
            <span>最近执行</span>
          </div>
          <ul v-if="recentTraces.length" class="trace-list">
            <li v-for="t in recentTraces" :key="t.traceId" class="trace-item">
              <span class="trace-ok" :class="{ fail: !t.executionOk }">{{ t.executionOk ? '✓' : '✗' }}</span>
              <div class="trace-body">
                <div class="trace-tool">{{ toolLabel(t.toolName) }}</div>
                <div class="trace-meta">{{ t.durationMs }}ms · {{ t.createdAt }}</div>
              </div>
            </li>
          </ul>
          <div v-else class="side-empty">暂无工具执行记录</div>
        </div>
      </aside>
    </div>

    <footer class="wall-footer">
      <span>数据源 {{ dataSource || '—' }}</span>
      <span>WS {{ wsConnected ? '已连接' : '未连接' }}</span>
      <span v-if="correlation.timestamp">巡检快照 {{ correlation.timestamp }}</span>
      <span v-if="correlation.cpuUsagePct != null">CPU {{ num(correlation.cpuUsagePct) }}%</span>
      <span v-if="correlation.diskUsagePct != null">磁盘 {{ num(correlation.diskUsagePct) }}%</span>
    </footer>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { Warning } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { useLiveWallFeed } from '../composables/useLiveWallFeed.js'
import { mcpToolDisplayName } from '../utils/mcpToolsMeta.js'
import {
  sparseBarLayout,
  formatChartDayLabel,
  pathBasename,
  formatMiBLabel
} from '../utils/chartOptions.js'

const {
  METRIC_DEFS,
  metricsMap,
  seriesRing,
  patrolAlerts,
  patrolTrend,
  correlation,
  diskHotspots,
  pendingRemediation,
  recentTraces,
  envSummary,
  lastUpdated,
  dataSource,
  wsConnected,
  loadError,
  refreshAll
} = useLiveWallFeed()

const clockText = ref('')
const isFullscreen = ref(false)
const trendRef = ref(null)
const treemapRef = ref(null)
const patrolRef = ref(null)
const gaugeRefs = {}

let clockTimer = null
let trendChart = null
let treemapChart = null
let patrolChart = null
const gaugeCharts = {}
let resizeObs = null

const headerChips = computed(() => {
  const chips = [{ key: 'env', text: envSummary.value.label, type: envSummary.value.dryRun ? 'warn' : 'ok' }]
  if (envSummary.value.ai) chips.push({ key: 'ai', text: 'AI 已配置', type: 'ok' })
  else chips.push({ key: 'ai', text: 'AI 未配置', type: 'muted' })
  if (envSummary.value.dryRun) chips.push({ key: 'dry', text: '全局演练', type: 'warn' })
  return chips
})

function setGaugeRef (key, el) {
  if (el) gaugeRefs[key] = el
}

function num (v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(1) : '—'
}

function formatPct (v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return `${n.toFixed(1)}%`
}

function heatColor (v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '#64748b'
  if (n >= 85) return '#f87171'
  if (n >= 60) return '#fbbf24'
  return '#34d399'
}

function alertKey (a, i) {
  return a.id || a.traceId || a.fingerprint || `a-${i}`
}

function alertTagType (level) {
  const u = String(level || '').toUpperCase()
  if (u.includes('HIGH') || u.includes('CRIT')) return 'danger'
  if (u.includes('WARN') || u.includes('MED')) return 'warning'
  return 'info'
}

function toolLabel (name) {
  return mcpToolDisplayName(name)
}

function gaugeOption (value, color) {
  const v = Number(value)
  const pct = Number.isFinite(v) ? Math.min(100, Math.max(0, v)) : 0
  return {
    animation: false,
    series: [
      {
        type: 'gauge',
        startAngle: 200,
        endAngle: -20,
        min: 0,
        max: 100,
        splitNumber: 4,
        radius: '92%',
        axisLine: {
          lineStyle: {
            width: 8,
            color: [
              [0.6, 'rgba(52,211,153,0.85)'],
              [0.85, 'rgba(251,191,36,0.9)'],
              [1, 'rgba(248,113,113,0.95)']
            ]
          }
        },
        pointer: { show: true, length: '58%', width: 4, itemStyle: { color } },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        detail: { show: false },
        data: [{ value: pct }]
      }
    ]
  }
}

function buildTrendOption () {
  return {
    animation: false,
    backgroundColor: 'transparent',
    color: METRIC_DEFS.map((m) => m.color),
    grid: { left: 44, right: 16, top: 28, bottom: 28 },
    legend: {
      data: METRIC_DEFS.map((m) => m.label),
      textStyle: { color: '#475569', fontSize: 11 },
      top: 0
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#e2e8f0',
      textStyle: { color: '#334155', fontSize: 12 }
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: [...seriesRing.labels],
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { color: '#64748b', fontSize: 10, interval: Math.max(0, Math.floor(seriesRing.labels.length / 8)) }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { color: '#64748b', fontSize: 10, formatter: '{value}%' },
      splitLine: { lineStyle: { color: 'rgba(226,232,240,0.95)' } }
    },
    series: [
      { name: 'CPU', type: 'line', smooth: true, showSymbol: false, data: [...seriesRing.cpu] },
      { name: '内存', type: 'line', smooth: true, showSymbol: false, data: [...seriesRing.memory] },
      { name: '磁盘', type: 'line', smooth: true, showSymbol: false, data: [...seriesRing.disk] },
      { name: '网络', type: 'line', smooth: true, showSymbol: false, data: [...seriesRing.network] }
    ]
  }
}

function formatHotspotSize(v) {
  return formatMiBLabel(v)
}

function hotspotLabel(h) {
  const path = String(h?.path || '')
  const base = pathBasename(path)
  const parent = path.replace(/\\/g, '/').split('/').filter(Boolean)
  const parentName = parent.length > 1 ? parent[parent.length - 2] : ''
  if (parentName && base !== path) {
    return `${parentName}/${base}`
  }
  return base || path || '—'
}

function buildTreemapOption () {
  const list = diskHotspots.value
  const children = list.map((h) => {
    const mib = Number(h.approxMiB) || 0
    const path = String(h.path || '/')
    const label = hotspotLabel(h)
    return {
      name: label,
      value: Math.max(1, mib),
      path,
      itemStyle: {
        color: mib >= 1024 ? '#f87171' : mib >= 256 ? '#fbbf24' : '#14b8a6'
      }
    }
  })
  return {
    animation: false,
    backgroundColor: 'transparent',
    tooltip: {
      formatter (p) {
        const d = p.data
        return `<div style="max-width:280px;word-break:break-all">${d.path || p.name}</div>约 ${formatHotspotSize(d.value)}`
      },
      backgroundColor: 'rgba(255,255,255,0.98)',
      borderColor: '#e2e8f0',
      textStyle: { color: '#334155', fontSize: 12 }
    },
    series: [
      {
        type: 'treemap',
        roam: false,
        nodeClick: false,
        breadcrumb: { show: false },
        label: {
          show: true,
          formatter (p) {
            const mib = Number(p.value) || 0
            return `${p.name}\n${formatHotspotSize(mib)}`
          },
          color: '#fff',
          fontSize: 11,
          lineHeight: 14
        },
        upperLabel: { show: false },
        itemStyle: { borderColor: '#ffffff', borderWidth: 2, gapWidth: 2 },
        data: children
      }
    ]
  }
}

function buildPatrolOption () {
  const list = patrolTrend.value
  const labels = list.map((r) => formatChartDayLabel(r.day))
  const alertCounts = list.map((r) => Number(r.alertCount ?? 0))
  const runCounts = list.map((r) => Number(r.runCount ?? 0))
  const barLayout = sparseBarLayout(list.length)
  return {
    animation: false,
    backgroundColor: 'transparent',
    grid: { left: 40, right: 12, top: 36, bottom: 28 },
    legend: {
      data: ['告警', '巡检'],
      textStyle: { color: '#475569', fontSize: 11 },
      top: 0
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#e2e8f0',
      textStyle: { color: '#334155' }
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { color: '#64748b', fontSize: 10 },
      axisTick: { alignWithLabel: true }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: '#64748b', fontSize: 10 },
      splitLine: { lineStyle: { color: 'rgba(226,232,240,0.95)' } }
    },
    series: [
      {
        name: '告警',
        type: 'bar',
        ...barLayout,
        data: alertCounts,
        itemStyle: { color: '#f59e0b', borderRadius: [3, 3, 0, 0] }
      },
      {
        name: '巡检',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        data: runCounts,
        lineStyle: { color: '#0d9488', width: 2 },
        itemStyle: { color: '#0d9488' }
      }
    ]
  }
}

function initCharts () {
  if (trendRef.value && !trendChart) trendChart = echarts.init(trendRef.value, null, { renderer: 'canvas' })
  if (treemapRef.value && !treemapChart) treemapChart = echarts.init(treemapRef.value, null, { renderer: 'canvas' })
  if (patrolRef.value && !patrolChart) patrolChart = echarts.init(patrolRef.value, null, { renderer: 'canvas' })
  METRIC_DEFS.forEach((m) => {
    const el = gaugeRefs[m.key]
    if (el && !gaugeCharts[m.key]) {
      gaugeCharts[m.key] = echarts.init(el, null, { renderer: 'canvas' })
    }
  })
}

function renderCharts () {
  initCharts()
  METRIC_DEFS.forEach((m) => {
    const ch = gaugeCharts[m.key]
    if (ch) ch.setOption(gaugeOption(metricsMap[m.key], m.color), true)
  })
  if (trendChart && seriesRing.labels.length) {
    trendChart.setOption(buildTrendOption(), true)
  }
  if (treemapChart && diskHotspots.value.length) {
    treemapChart.setOption(buildTreemapOption(), true)
  }
  if (patrolChart && patrolTrend.value.length) {
    patrolChart.setOption(buildPatrolOption(), true)
  }
  resizeCharts()
}

function resizeCharts () {
  trendChart?.resize()
  treemapChart?.resize()
  patrolChart?.resize()
  Object.values(gaugeCharts).forEach((c) => c?.resize())
}

function toggleFullscreen () {
  if (!document.fullscreenElement) {
    document.documentElement.requestFullscreen?.().then(() => {
      isFullscreen.value = true
    }).catch(() => {
      isFullscreen.value = !isFullscreen.value
    })
  } else {
    document.exitFullscreen?.()
    isFullscreen.value = false
  }
}

function onFullscreenChange () {
  isFullscreen.value = !!document.fullscreenElement
  nextTick(resizeCharts)
}

watch([metricsMap, seriesRing], () => nextTick(renderCharts), { deep: true })
watch([diskHotspots, patrolTrend], () => nextTick(renderCharts), { deep: true })

onMounted(() => {
  const tick = () => {
    const d = new Date()
    clockText.value = d.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    })
  }
  tick()
  clockTimer = setInterval(tick, 1000)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  nextTick(() => {
    initCharts()
    renderCharts()
    if (typeof ResizeObserver !== 'undefined') {
      resizeObs = new ResizeObserver(() => resizeCharts())
      ;[trendRef, treemapRef, patrolRef].forEach((r) => {
        if (r.value) resizeObs.observe(r.value)
      })
    }
  })
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  resizeObs?.disconnect()
  trendChart?.dispose()
  treemapChart?.dispose()
  patrolChart?.dispose()
  Object.values(gaugeCharts).forEach((c) => c?.dispose())
})
</script>

<style scoped>
.ops-live-wall {
  --wall-bg: #ffffff;
  --wall-panel: #ffffff;
  --wall-border: #e2e8f0;
  --wall-text: #0f172a;
  --wall-muted: #64748b;
  --wall-accent: #0d9488;
  min-height: calc(100vh - 120px);
  margin: 0;
  padding: 16px 18px 12px;
  background: radial-gradient(ellipse 120% 80% at 50% -20%, rgba(13, 148, 136, 0.06), transparent 55%),
    linear-gradient(180deg, #f8fafc 0%, var(--wall-bg) 100%);
  color: var(--wall-text);
  display: flex;
  flex-direction: column;
  gap: 12px;
  border-radius: var(--ops-radius, 10px);
}

.wall-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 900;
  min-height: 100vh;
  margin: 0;
  border-radius: 0;
  padding: 20px 24px;
}

.wall-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--wall-border);
}

.wall-brand {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.wall-title {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.06em;
  background: linear-gradient(90deg, #0d9488, #0284c7);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.wall-sub {
  font-size: 12px;
  color: var(--wall-muted);
}

.wall-header-center {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.wall-chip {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--wall-border);
  background: #f8fafc;
  color: var(--wall-muted);
}

.wall-chip.ok {
  border-color: rgba(13, 148, 136, 0.35);
  background: rgba(13, 148, 136, 0.08);
  color: #0f766e;
}

.wall-chip.warn {
  border-color: rgba(245, 158, 11, 0.45);
  background: rgba(245, 158, 11, 0.08);
  color: #b45309;
}

.wall-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--wall-muted);
}

.wall-clock {
  font-variant-numeric: tabular-nums;
  font-size: 14px;
  color: var(--wall-text);
  font-weight: 600;
}

.wall-err {
  color: #f87171;
}

.wall-btn {
  --el-button-bg-color: #ffffff;
  --el-button-border-color: var(--wall-border);
  --el-button-text-color: var(--wall-text);
}

.wall-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 8px;
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.35);
  color: #b45309;
  font-size: 13px;
}

.wall-body {
  flex: 1;
  display: grid;
  grid-template-columns: 200px 1fr 300px;
  gap: 12px;
  min-height: 520px;
}

@media (max-width: 1280px) {
  .wall-body {
    grid-template-columns: 1fr;
  }
  .wall-gauges {
    grid-template-columns: repeat(4, 1fr) !important;
    flex-direction: row !important;
  }
}

.wall-gauges {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.gauge-panel {
  flex: 1;
  background: var(--wall-panel);
  border: 1px solid var(--wall-border);
  border-radius: 10px;
  padding: 8px 10px 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 100px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}

.gauge-label {
  font-size: 11px;
  color: var(--wall-muted);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  align-self: flex-start;
}

.gauge-chart {
  width: 100%;
  height: 72px;
  flex: 1;
}

.gauge-val {
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  margin-top: -4px;
}

.wall-center {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.panel {
  background: var(--wall-panel);
  border: 1px solid var(--wall-border);
  border-radius: 10px;
  padding: 10px 12px 8px;
  position: relative;
  min-height: 0;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
}

.panel-trend {
  flex: 1.2;
  min-height: 240px;
}

.panel-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  flex: 1;
  min-height: 200px;
}

@media (max-width: 900px) {
  .panel-row {
    grid-template-columns: 1fr;
  }
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
  color: var(--wall-text);
}

.panel-meta {
  font-size: 11px;
  font-weight: 400;
  color: var(--wall-muted);
}

.chart-box {
  width: 100%;
  height: calc(100% - 28px);
  min-height: 180px;
}

.chart-box-sm {
  min-height: 120px;
  height: 120px;
}

.hotspot-table {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  max-height: 120px;
  overflow-y: auto;
}

.hotspot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 12px;
}

.hotspot-row:nth-child(odd) {
  background: rgba(241, 245, 249, 0.8);
}

.hotspot-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--wall-text);
  font-family: var(--ops-font-mono);
}

.hotspot-size {
  flex-shrink: 0;
  font-weight: 600;
  color: #0f766e;
  font-variant-numeric: tabular-nums;
}

.chart-empty,
.side-empty {
  position: absolute;
  inset: 36px 12px 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--wall-muted);
  pointer-events: none;
  text-align: center;
  padding: 12px;
}

.wall-side {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.panel-alerts {
  flex: 1.2;
  min-height: 200px;
  max-height: 340px;
  display: flex;
  flex-direction: column;
}

.panel-traces {
  flex: 1;
  min-height: 160px;
  max-height: 280px;
  overflow: hidden;
}

.alert-scroll {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  flex: 1;
  max-height: 280px;
}

.alert-item {
  display: flex;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f1f5f9;
}

.alert-body {
  min-width: 0;
  flex: 1;
}

.alert-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--wall-text);
}

.alert-detail {
  font-size: 11px;
  color: var(--wall-muted);
  margin-top: 2px;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.alert-ts {
  font-size: 10px;
  color: #64748b;
  margin-top: 4px;
}

.trace-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  max-height: 220px;
}

.trace-item {
  display: flex;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid #f1f5f9;
  font-size: 11px;
}

.trace-ok {
  color: #34d399;
  font-weight: 700;
}

.trace-ok.fail {
  color: #f87171;
}

.trace-tool {
  font-weight: 600;
  color: var(--wall-text);
}

.trace-meta {
  color: var(--wall-muted);
  margin-top: 2px;
}

.wall-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 11px;
  color: var(--wall-muted);
  padding-top: 8px;
  border-top: 1px solid var(--wall-border);
}
</style>
