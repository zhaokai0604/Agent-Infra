<template>
  <div class="ops-page ops-effect-dashboard">
    <OpsPageHeader
      title="运维效果评分"
      :subtitle="`近 ${periodDays} 日综合表现 · 五维能力雷达与审计/巡检趋势`"
    >
      <template #actions>
        <el-radio-group v-model="periodDays" size="small" @change="loadData">
          <el-radio-button :value="7">7 日</el-radio-button>
          <el-radio-button :value="14">14 日</el-radio-button>
          <el-radio-button :value="30">30 日</el-radio-button>
        </el-radio-group>
        <el-button type="primary" :loading="loading" @click="loadData">刷新</el-button>
      </template>
    </OpsPageHeader>

    <div v-loading="loading" class="effect-body">
      <template v-if="data">
        <!-- 综合概览 -->
        <section class="overview-card">
          <div class="overview-gauge">
            <div ref="gaugeRef" class="gauge-chart" />
            <div class="gauge-foot">
              <span class="gauge-grade" :style="{ color: overallGradeColor }">{{ data.overallGrade }}</span>
              <span class="gauge-window">{{ data.periodDays }} 日窗口</span>
            </div>
          </div>

          <div class="overview-copy">
            <p v-if="summaryHeadline" class="overview-headline">{{ summaryHeadline }}</p>
            <ul v-if="summaryBullets.length" class="overview-bullets">
              <li v-for="(b, i) in summaryBullets" :key="i">{{ b }}</li>
            </ul>
            <div v-if="highlights.length" class="overview-tags">
              <el-tag
                v-for="(h, i) in highlights"
                :key="i"
                :type="highlightTagType(h.type)"
                effect="plain"
                size="small"
                round
              >
                {{ h.title }}
              </el-tag>
            </div>
          </div>
        </section>

        <!-- 五维评分 -->
        <section class="section-block">
          <div class="section-head">
            <h2>五维评分</h2>
            <span class="section-hint">点击维度查看说明，悬停详情可看完整描述</span>
          </div>
          <div class="dim-grid">
            <article
              v-for="dim in dimensionList"
              :key="dim.id"
              class="dim-card"
            >
              <div class="dim-card__head">
                <span class="dim-card__name">{{ dim.name }}</span>
                <div class="dim-card__score">
                  <strong :style="{ color: scoreColor(dim.score) }">{{ displayDimensionScore(dim) }}</strong>
                  <span class="dim-card__grade">{{ dim.grade }}</span>
                </div>
              </div>
              <el-progress
                v-if="isMeasuredDimension(dim)"
                :percentage="normalizedDimensionScore(dim)"
                :stroke-width="6"
                :show-text="false"
                :color="scoreColor(dim.score)"
              />
              <div v-else class="dim-card__progress-placeholder">暂无真实样本</div>
              <p class="dim-card__detail" :title="dim.detail">{{ dim.detail }}</p>
            </article>
          </div>
        </section>

        <!-- 补充指标（不与五维重复） -->
        <section v-if="supplementaryMetrics.length" class="supp-row">
          <div v-for="m in supplementaryMetrics" :key="m.key" class="supp-item">
            <span class="supp-item__val">{{ m.value }}</span>
            <span class="supp-item__label">{{ m.label }}</span>
          </div>
          <div v-if="resourceLine" class="supp-item supp-item--resource">
            <span class="supp-item__val supp-item__val--text">
              <el-icon><Odometer /></el-icon>
            </span>
            <span class="supp-item__label" :title="resourceLine">{{ resourceLine }}</span>
          </div>
        </section>

        <!-- 图表 -->
        <section class="section-block">
          <div class="section-head">
            <h2>趋势分析</h2>
          </div>
          <div class="charts-grid">
            <el-card class="chart-panel" shadow="never">
              <template #header><span class="chart-panel__title">五维能力雷达</span></template>
              <div ref="radarRef" class="chart-panel__box chart-panel__box--radar" />
            </el-card>
            <el-card class="chart-panel" shadow="never">
              <template #header>
                <span class="chart-panel__title">审计处置趋势</span>
                <span v-if="auditDayCount" class="chart-panel__meta">{{ auditDayCount }} 天</span>
              </template>
              <div ref="auditTrendRef" class="chart-panel__box" />
            </el-card>
            <el-card class="chart-panel" shadow="never">
              <template #header>
                <span class="chart-panel__title">巡检告警趋势</span>
                <span v-if="patrolDayCount" class="chart-panel__meta">{{ patrolDayCount }} 天</span>
              </template>
              <div ref="patrolTrendRef" class="chart-panel__box" />
            </el-card>
            <el-card class="chart-panel" shadow="never">
              <template #header>
                <span class="chart-panel__title">资源指标趋势</span>
                <span v-if="metricTrendCount" class="chart-panel__meta">{{ metricTrendCount }} 点</span>
              </template>
              <div ref="metricTrendRef" class="chart-panel__box" />
            </el-card>
          </div>
        </section>

        <el-card v-if="recentHealing.length" class="healing-table" shadow="never">
          <template #header><span class="chart-panel__title">近期自愈评分</span></template>
          <el-table :data="recentHealing" size="small" stripe>
            <el-table-column prop="traceId" label="Trace ID" min-width="200" show-overflow-tooltip />
            <el-table-column prop="channel" label="通道" width="100" />
            <el-table-column prop="healingScore" label="评分" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="healingTagType(row.healingScore)">
                  {{ row.healingScore }}/100
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" min-width="160" />
          </el-table>
        </el-card>
      </template>

      <el-empty v-else-if="!loading" description="暂无评分数据，请先产生审计或巡检记录" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { Odometer } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { loadEcharts } from '../utils/echartsLoader.js'
import { getOpsEffectDashboard } from '../api/index.js'
import OpsPageHeader from './OpsPageHeader.vue'
import {
  sparseBarLayout,
  formatChartDayLabel,
  chartEmptyGraphic
} from '../utils/chartOptions.js'

const loading = ref(false)
const data = ref(null)
const periodDays = ref(7)

const gaugeRef = ref(null)
const radarRef = ref(null)
const auditTrendRef = ref(null)
const patrolTrendRef = ref(null)
const metricTrendRef = ref(null)
let gaugeChart = null
let radarChart = null
let auditChart = null
let patrolChart = null
let metricChart = null
let echartsApi = null

async function echarts() {
  if (!echartsApi) {
    const mod = await loadEcharts()
    echartsApi = mod.default || mod
  }
  return echartsApi
}

const summaryHeadline = computed(() => data.value?.summary?.headline || '')
const summaryBullets = computed(() => {
  const b = data.value?.summary?.bullets
  return Array.isArray(b) ? b : []
})
const highlights = computed(() => {
  const h = data.value?.valueHighlights
  return Array.isArray(h) ? h : []
})
const dimensionList = computed(() => {
  const dims = data.value?.dimensions
  if (!dims || typeof dims !== 'object') return []
  return ['security', 'execution', 'patrol', 'resource', 'healing']
    .map((id) => dims[id])
    .filter(Boolean)
})
const overallGradeColor = computed(() => gradeColor(data.value?.overallGrade))

const supplementaryMetrics = computed(() => {
  const k = data.value?.kpis || {}
  const items = [
    { key: 'confirm', label: '二次确认（次）', value: k.auditNeedConfirm ?? 0 },
    {
      key: 'heal',
      label: '自愈均分',
      value: k.healingSamples > 0 ? Math.round(k.avgHealingScore) : '—'
    },
    { key: 'audit', label: '审计总量', value: k.auditTotal ?? 0 }
  ]
  const space = formatReleasedGb(k.releasedSpaceGb)
  if (space !== '0') {
    items.unshift({ key: 'space', label: '释放空间（GB）', value: space })
  }
  return items
})

const recentHealing = computed(() => {
  const r = data.value?.recentEffectRuns
  return Array.isArray(r) ? r : []
})

const auditDayCount = computed(() => data.value?.trends?.auditByDay?.length || 0)
const patrolDayCount = computed(() => data.value?.trends?.patrolByDay?.length || 0)
const metricTrendCount = computed(() => data.value?.trends?.metricTrend?.length || 0)

function formatReleasedGb(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return '0'
  return n < 0.01 ? '<0.01' : n.toFixed(2)
}

const resourceLine = computed(() => {
  const r = data.value?.currentResource
  if (!r) return ''
  const fmt = (v) => (v != null && v !== '' ? `${Number(v).toFixed(1)}%` : '—')
  return `CPU ${fmt(r.cpuUsagePct)} · 内存 ${fmt(r.memoryUsagePct)} · 磁盘 ${fmt(r.diskUsagePct)}`
})

function scoreColor(score) {
  const n = Number(score)
  if (!Number.isFinite(n)) return '#94a3b8'
  if (score >= 85) return '#16a34a'
  if (score >= 70) return '#0d9488'
  if (score >= 55) return '#d97706'
  return '#dc2626'
}

function isMeasuredDimension(dim) {
  if (!dim || dim.measured === false) return false
  return Number.isFinite(Number(dim.score))
}

function normalizedDimensionScore(dim) {
  if (!isMeasuredDimension(dim)) return 0
  return Number(dim.score)
}

function displayDimensionScore(dim) {
  return isMeasuredDimension(dim) ? String(dim.score) : '—'
}

function gradeColor(grade) {
  if (grade === '优秀') return '#16a34a'
  if (grade === '良好') return '#0d9488'
  if (grade === '合格') return '#2563eb'
  if (grade === '待提升') return '#dc2626'
  return '#64748b'
}

function highlightTagType(type) {
  if (type === 'security') return 'danger'
  if (type === 'healing') return 'success'
  if (type === 'patrol') return 'warning'
  return 'info'
}

function healingTagType(score) {
  const n = Number(score)
  if (n >= 70) return 'success'
  if (n >= 45) return 'warning'
  return 'info'
}

async function renderGauge() {
  if (!gaugeRef.value || !data.value) return
  const ec = await echarts()
  if (!gaugeChart) gaugeChart = ec.init(gaugeRef.value)
  const score = Number(data.value.overallScore) || 0
  const accent = scoreColor(score)
  gaugeChart.setOption({
    animationDuration: 600,
    series: [{
      type: 'gauge',
      center: ['50%', '58%'],
      radius: '88%',
      startAngle: 220,
      endAngle: -40,
      min: 0,
      max: 100,
      splitNumber: 4,
      progress: {
        show: true,
        width: 12,
        roundCap: true,
        itemStyle: { color: accent }
      },
      pointer: {
        show: true,
        length: '55%',
        width: 4,
        itemStyle: { color: '#334155' }
      },
      axisLine: {
        roundCap: true,
        lineStyle: { width: 12, color: [[1, '#e2e8f0']] }
      },
      axisTick: { show: false },
      splitLine: { show: false },
      axisLabel: {
        distance: 14,
        fontSize: 10,
        color: '#94a3b8',
        formatter: (v) => (v % 25 === 0 ? String(v) : '')
      },
      anchor: { show: false },
      title: { show: false },
      detail: {
        valueAnimation: true,
        fontSize: 32,
        fontWeight: 700,
        color: accent,
        offsetCenter: [0, '12%'],
        formatter: '{value}'
      },
      data: [{ value: score }]
    }]
  }, true)
}

async function renderRadar() {
  if (!radarRef.value || !data.value) return
  const ec = await echarts()
  if (!radarChart) radarChart = ec.init(radarRef.value)
  const radar = data.value.trends?.dimensionRadar || []
  radarChart.setOption({
    tooltip: {},
    graphic: radar.length ? undefined : [chartEmptyGraphic('暂无已测评分维度')],
    radar: {
      indicator: radar.map((r) => ({ name: r.name, max: 100 })),
      radius: '65%',
      splitNumber: 4,
      axisName: { color: '#64748b', fontSize: 11 },
      splitArea: { areaStyle: { color: ['#f8fafc', '#fff'] } },
      splitLine: { lineStyle: { color: '#e2e8f0' } },
      axisLine: { lineStyle: { color: '#cbd5e1' } }
    },
    series: [{
      type: 'radar',
      data: [{
        value: radar.map((r) => r.value),
        name: '能力分',
        areaStyle: { color: 'rgba(13, 148, 136, 0.15)' },
        lineStyle: { width: 2, color: '#0d9488' },
        itemStyle: { color: '#0d9488' }
      }]
    }]
  }, true)
}

async function renderAuditTrend() {
  if (!auditTrendRef.value || !data.value) return
  const ec = await echarts()
  if (!auditChart) auditChart = ec.init(auditTrendRef.value)
  const rows = data.value.trends?.auditByDay || []
  const barLayout = sparseBarLayout(rows.length)
  auditChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' }
    },
    legend: { data: ['审计总量', '成功执行', '安全拦截'], bottom: 0, textStyle: { fontSize: 11 } },
    grid: { left: 44, right: 16, top: 28, bottom: 52 },
    graphic: rows.length ? undefined : [chartEmptyGraphic('暂无审计趋势')],
    xAxis: {
      type: 'category',
      data: rows.map((r) => formatChartDayLabel(r.day)),
      axisLabel: { color: '#64748b', fontSize: 11 },
      axisTick: { alignWithLabel: true }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#64748b', fontSize: 10 }
    },
    series: [
      {
        name: '审计总量',
        type: 'bar',
        ...barLayout,
        data: rows.map((r) => r.total),
        itemStyle: { color: '#cbd5e1', borderRadius: [4, 4, 0, 0] }
      },
      {
        name: '成功执行',
        type: 'bar',
        ...barLayout,
        data: rows.map((r) => r.successExec),
        itemStyle: { color: '#22c55e', borderRadius: [4, 4, 0, 0] }
      },
      {
        name: '安全拦截',
        type: 'bar',
        ...barLayout,
        data: rows.map((r) => r.blocked),
        itemStyle: { color: '#ef4444', borderRadius: [4, 4, 0, 0] }
      }
    ]
  }, true)
}

async function renderPatrolTrend() {
  if (!patrolTrendRef.value || !data.value) return
  const ec = await echarts()
  if (!patrolChart) patrolChart = ec.init(patrolTrendRef.value)
  const rows = data.value.trends?.patrolByDay || []
  const barLayout = sparseBarLayout(rows.length)
  patrolChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['巡检轮次', '告警线索'], bottom: 0, textStyle: { fontSize: 11 } },
    grid: { left: 44, right: 16, top: 28, bottom: 52 },
    graphic: rows.length ? undefined : [chartEmptyGraphic('暂无巡检趋势')],
    xAxis: {
      type: 'category',
      data: rows.map((r) => formatChartDayLabel(r.day)),
      axisLabel: { color: '#64748b', fontSize: 11 },
      axisTick: { alignWithLabel: true }
    },
    yAxis: [
      {
        type: 'value',
        name: '轮次',
        minInterval: 1,
        splitLine: { lineStyle: { color: '#f1f5f9' } },
        axisLabel: { color: '#64748b', fontSize: 10 }
      },
      {
        type: 'value',
        name: '告警',
        minInterval: 1,
        splitLine: { show: false },
        axisLabel: { color: '#64748b', fontSize: 10 }
      }
    ],
    series: [
      {
        name: '巡检轮次',
        type: 'bar',
        ...barLayout,
        data: rows.map((r) => r.runCount),
        itemStyle: { color: '#0d9488', borderRadius: [4, 4, 0, 0] }
      },
      {
        name: '告警线索',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        data: rows.map((r) => r.alertCount),
        itemStyle: { color: '#d97706' },
        lineStyle: { width: 2.5 }
      }
    ]
  }, true)
}

function formatMetricTimestamp(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length > 16 ? text.slice(5, 16) : text
}

async function renderMetricTrend() {
  if (!metricTrendRef.value || !data.value) return
  const ec = await echarts()
  if (!metricChart) metricChart = ec.init(metricTrendRef.value)
  const rows = data.value.trends?.metricTrend || []
  metricChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['CPU', 'Memory', 'Disk'], bottom: 0, textStyle: { fontSize: 11 } },
    grid: { left: 44, right: 16, top: 28, bottom: 52 },
    graphic: rows.length ? undefined : [chartEmptyGraphic('No resource trend data')],
    xAxis: {
      type: 'category',
      data: rows.map((r) => formatMetricTimestamp(r.timestamp)),
      axisLabel: { color: '#64748b', fontSize: 10 },
      axisTick: { alignWithLabel: true }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#64748b', fontSize: 10, formatter: '{value}%' }
    },
    series: [
      {
        name: 'CPU',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        data: rows.map((r) => r.cpuUsagePct ?? null),
        itemStyle: { color: '#0d9488' },
        lineStyle: { width: 2 }
      },
      {
        name: 'Memory',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        data: rows.map((r) => r.memoryUsagePct ?? null),
        itemStyle: { color: '#2563eb' },
        lineStyle: { width: 2 }
      },
      {
        name: 'Disk',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        data: rows.map((r) => r.diskUsagePct ?? null),
        itemStyle: { color: '#d97706' },
        lineStyle: { width: 2 }
      }
    ]
  }, true)
}

function renderCharts() {
  nextTick(() => {
    renderGauge()
    renderRadar()
    renderAuditTrend()
    renderPatrolTrend()
    renderMetricTrend()
    gaugeChart?.resize()
    radarChart?.resize()
    auditChart?.resize()
    patrolChart?.resize()
    metricChart?.resize()
  })
}

async function loadData() {
  loading.value = true
  try {
    data.value = await getOpsEffectDashboard(periodDays.value)
    renderCharts()
  } catch (e) {
    ElMessage.error(e?.message || '加载运维效果评分失败')
    data.value = null
  } finally {
    loading.value = false
  }
}

function onResize() {
  gaugeChart?.resize()
  radarChart?.resize()
  auditChart?.resize()
  patrolChart?.resize()
  metricChart?.resize()
}

watch(data, () => renderCharts())

onMounted(() => {
  loadData()
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  gaugeChart?.dispose()
  radarChart?.dispose()
  auditChart?.dispose()
  patrolChart?.dispose()
  metricChart?.dispose()
})
</script>

<style scoped>
.ops-effect-dashboard {
  padding: 0;
}

.effect-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ── 概览 ── */
.overview-card {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 24px;
  align-items: center;
  padding: 20px 24px;
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius);
  background: var(--ops-panel);
}

.overview-gauge {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.gauge-chart {
  width: 220px;
  height: 200px;
}

.gauge-foot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  margin-top: -6px;
}

.gauge-grade {
  font-size: 15px;
  font-weight: 700;
}

.gauge-window {
  font-size: 12px;
  color: var(--ops-text-subtle);
}

.overview-headline {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--ops-text);
}

.overview-bullets {
  margin: 0 0 14px;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--ops-text-subtle);
}

.overview-bullets li::marker {
  color: var(--ops-primary);
}

.overview-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* ── 区块标题 ── */
.section-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.section-head h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--ops-text);
}

.section-hint {
  font-size: 12px;
  color: var(--ops-text-subtle);
}

/* ── 五维卡片 ── */
.dim-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.dim-card {
  padding: 14px 14px 12px;
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius-sm);
  background: var(--ops-panel);
}

.dim-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.dim-card__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ops-text);
  line-height: 1.3;
}

.dim-card__score {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  flex-shrink: 0;
}

.dim-card__score strong {
  font-size: 22px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.dim-card__grade {
  font-size: 11px;
  color: var(--ops-text-subtle);
  margin-top: 2px;
}

.dim-card__detail {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--ops-text-subtle);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.dim-card__progress-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 16px;
  margin-top: 2px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #94a3b8;
  font-size: 11px;
}

/* ── 补充指标 ── */
.supp-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--ops-border-soft);
  border-radius: var(--ops-radius-sm);
  background: var(--ops-panel-soft);
}

.supp-item {
  flex: 1 1 120px;
  min-width: 100px;
  padding: 10px 12px;
  border-radius: var(--ops-radius-sm);
  background: var(--ops-panel);
  border: 1px solid var(--ops-border);
  text-align: center;
}

.supp-item--resource {
  flex: 2 1 200px;
  text-align: left;
}

.supp-item__val {
  display: block;
  font-size: 20px;
  font-weight: 700;
  color: var(--ops-primary);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.supp-item__val--text {
  font-size: 16px;
  color: var(--ops-text-subtle);
}

.supp-item__label {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--ops-text-subtle);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.supp-item--resource .supp-item__label {
  white-space: normal;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

/* ── 图表 ── */
.charts-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.chart-panel {
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius-sm);
  overflow: hidden;
}

.chart-panel :deep(.el-card__body) {
  padding: 8px 10px 12px;
}

.chart-panel :deep(.el-card__header) {
  padding: 10px 14px;
  background: var(--ops-panel-soft);
  border-bottom: 1px solid var(--ops-border-soft);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.chart-panel__meta {
  font-size: 11px;
  color: var(--ops-text-subtle);
  font-weight: normal;
}

.chart-panel__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ops-text);
}

.chart-panel__box {
  height: 240px;
  width: 100%;
}

.chart-panel__box--radar {
  height: 240px;
}

.healing-table {
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius-sm);
}

@media (max-width: 900px) {
  .overview-card {
    grid-template-columns: 1fr;
    text-align: center;
  }

  .overview-copy {
    text-align: left;
  }
}

@media (max-width: 1200px) {
  .dim-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1100px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 520px) {
  .dim-grid {
    grid-template-columns: 1fr;
  }
}
</style>
