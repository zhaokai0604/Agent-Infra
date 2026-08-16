<template>
  <div class="ops-page log-analysis-dashboard">
    <OpsPageHeader
      title="证据趋势看板"
      subtitle="近 7 日日志证据、异常率、任务状态与系统性能趋势"
    >
      <template #actions>
        <el-button type="success" plain size="small" @click="goToOpsAgent">带入任务台</el-button>
        <el-button type="primary" @click="refreshData">刷新数据</el-button>
        <el-button type="info" plain size="small" @click="exportDashboardPng">导出 PNG</el-button>
        <el-button type="info" plain size="small" @click="exportDashboardPdf">导出 PDF</el-button>
      </template>
    </OpsPageHeader>

    <el-card class="dashboard-card" shadow="never">
      <!-- 统计卡片 -->
      <div class="stats-row">
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">近7日日志数</div>
            <div class="stat-value">{{ logSummary.totalLogs || 0 }}</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">近7日异常数</div>
            <div class="stat-value">{{ logSummary.anomalyLogs || 0 }}</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">近7日异常率</div>
            <div class="stat-value">{{ (logSummary.anomalyRate || 0).toFixed(2) }}%</div>
          </div>
        </el-card>
        <el-card class="stat-card">
          <div class="stat-item">
            <div class="stat-label">近7日任务数</div>
            <div class="stat-value">{{ logSummary.totalTasks || 0 }}</div>
          </div>
        </el-card>
      </div>

      <!-- 图表区域 -->
      <div class="charts-row">
        <!-- 证据分析趋势 -->
        <el-card class="chart-card">
          <template #header>
            <span>证据分析趋势</span>
          </template>
          <div ref="trendChartRef" class="chart-container echarts-host"></div>
        </el-card>

        <!-- 异常类型分布 -->
        <el-card class="chart-card">
          <template #header>
            <span>异常类型分布</span>
          </template>
          <div ref="distributionChartRef" class="chart-container echarts-host"></div>
        </el-card>
      </div>

      <div class="charts-row">
        <!-- 任务状态统计 -->
        <el-card class="chart-card">
          <template #header>
            <span>任务状态统计</span>
          </template>
          <div ref="taskStatusChartRef" class="chart-container echarts-host"></div>
        </el-card>

        <!-- 系统性能 -->
        <el-card class="chart-card">
          <template #header>
            <span>系统性能</span>
          </template>
          <div ref="performanceChartRef" class="chart-container echarts-host"></div>
        </el-card>
      </div>

      <!-- 最近分析任务 -->
      <el-card class="table-card">
        <template #header>
          <span>最近分析任务</span>
        </template>
        <el-table :data="recentTasks" style="width: 100%">
          <el-table-column prop="taskId" label="任务ID" width="200">
            <template #default="scope">
              <span class="task-id">{{ scope.row.taskId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="fileName" label="文件名"></el-table-column>
          <el-table-column prop="status" label="状态" width="120">
            <template #default="scope">
              <el-tag :type="getStatusType(scope.row.status)">
                {{ scope.row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="progress" label="进度" width="120">
            <template #default="scope">
              <el-progress :percentage="scope.row.progress" :stroke-width="10"></el-progress>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="180">
            <template #default="scope">
              {{ formatDateTime(scope.row.createTime) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="280">
            <template #default="scope">
              <el-button size="small" type="primary" @click="viewTask(scope.row.taskId)">
                查看详情
              </el-button>
              <el-button
                v-if="scope.row.status === 'FAILED'"
                size="small"
                type="warning"
                plain
                @click="opsAgentForFailedTask(scope.row)"
              >
                去对话排查
              </el-button>
              <el-button
                v-if="shouldOfferAgentDeepDive(scope.row)"
                size="small"
                type="success"
                plain
                @click="opsAgentDeepDiveTask(scope.row)"
              >
                继续分析
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, defineEmits } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { getLogSummary, getTaskStatusStatistics, getPerformance, getHistory } from '../api/index.js'
import { formatLocalDateKey, formatDateTime } from '../utils/formatDate.js'
import { exportDashboardPdfDocument, chartToPngDataUrl } from '../utils/dashboardExport.js'
import {
  dispatchOpsAgentPrefill,
  shouldOfferAgentDeepDive,
  agentPrefillHighAnomaly,
  agentPrefillFailedTask
} from '../utils/opsAgentNavigate'
import OpsPageHeader from './OpsPageHeader.vue'

// 定义事件
const emit = defineEmits(['view-task'])

// 响应式数据
const logSummary = ref({})
const recentTasks = ref([])
const trendChartRef = ref(null)
const distributionChartRef = ref(null)
const taskStatusChartRef = ref(null)
const performanceChartRef = ref(null)

// 图表实例
let trendChart = null
let distributionChart = null
let taskStatusChart = null
let performanceChart = null

const TREND_DAYS = 7

const downloadDataUrl = (dataUrl, filename) => {
  const a = document.createElement('a')
  a.href = dataUrl
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

const exportChartPng = (chart, filename) => {
  if (!chart) return false
  try {
    const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
    downloadDataUrl(url, filename)
    return true
  } catch {
    return false
  }
}

const exportDashboardPng = () => {
  const items = [
    [trendChart, 'log-trend-7d.png'],
    [distributionChart, 'anomaly-distribution.png'],
    [taskStatusChart, 'task-status.png'],
    [performanceChart, 'system-performance.png']
  ]
  let n = 0
  for (const [chart, name] of items) {
    if (exportChartPng(chart, name)) n += 1
  }
  if (n > 0) {
    ElMessage.success(`已导出 ${n} 张图表 PNG`)
  } else {
    ElMessage.warning('图表尚未就绪，请先刷新数据')
  }
}

const exportDashboardPdf = async () => {
  const charts = [trendChart, distributionChart, taskStatusChart, performanceChart]
  const images = charts.map((c) => chartToPngDataUrl(c)).filter(Boolean)
  if (images.length === 0) {
    ElMessage.warning('图表尚未就绪，请先刷新数据')
    return
  }
  const summary = logSummary.value || {}
  const summaryLine =
    `近7日：日志 ${summary.totalLogs ?? 0} · 异常 ${summary.anomalyLogs ?? 0} · 异常率 ${(summary.anomalyRate ?? 0).toFixed(2)}% · 任务 ${summary.totalTasks ?? 0}`
  try {
    await exportDashboardPdfDocument({
      title: '证据趋势看板 · 近7日',
      summaryLine,
      chartImages: images,
      filename: `log-dashboard-report-${formatLocalDateKey(new Date())}.pdf`
    })
    ElMessage.success('已导出 PDF 报告')
  } catch (e) {
    ElMessage.error(e?.message || 'PDF 导出失败')
  }
}

/** MyBatis Map 键名大小写兼容 */
const pickField = (row, ...keys) => {
  if (!row || typeof row !== 'object') return undefined
  const norm = {}
  for (const [k, v] of Object.entries(row)) {
    norm[String(k).toLowerCase()] = v
  }
  for (const k of keys) {
    const v = norm[String(k).toLowerCase()]
    if (v !== undefined && v !== null) return v
  }
  return undefined
}

const formatTrendDate = (raw) => {
  if (raw == null) return ''
  if (raw instanceof Date) return formatLocalDateKey(raw)
  if (typeof raw === 'string') return raw.slice(0, 10)
  return String(raw).slice(0, 10)
}

const buildTrendSeries = (taskTrend, days = TREND_DAYS) => {
  const countByDate = new Map()
  const list = Array.isArray(taskTrend) ? taskTrend : []
  for (const item of list) {
    const d = formatTrendDate(pickField(item, 'date', 'task_date', 'day'))
    const c = Number(pickField(item, 'count', 'cnt', 'total') || 0)
    if (d) countByDate.set(d, c)
  }
  const labels = []
  const values = []
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(d.getDate() - i)
    const key = formatLocalDateKey(d)
    labels.push(key)
    values.push(countByDate.get(key) ?? 0)
  }
  return { labels, values }
}

const scheduleChartResize = () => {
  nextTick(() => {
    requestAnimationFrame(() => {
      trendChart?.resize()
      distributionChart?.resize()
      taskStatusChart?.resize()
      performanceChart?.resize()
    })
  })
}

// 初始化图表
const initCharts = () => {
  // 证据分析趋势图表
  if (trendChartRef.value) {
    trendChart = echarts.init(trendChartRef.value)
    trendChart.setOption({
      tooltip: {
        trigger: 'axis'
      },
      xAxis: {
        type: 'category',
        data: []
      },
      yAxis: {
        type: 'value'
      },
      series: [{
        data: [],
        type: 'line',
        smooth: true
      }]
    })
  }

  // 异常类型分布图表
  if (distributionChartRef.value) {
    distributionChart = echarts.init(distributionChartRef.value)
    distributionChart.setOption({
      tooltip: {
        trigger: 'item'
      },
      legend: {
        orient: 'vertical',
        left: 'left'
      },
      series: [{
        type: 'pie',
        radius: '60%',
        data: [],
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowOffsetX: 0,
            shadowColor: 'rgba(0, 0, 0, 0.5)'
          }
        }
      }]
    })
  }

  // 任务状态统计图表
  if (taskStatusChartRef.value) {
    taskStatusChart = echarts.init(taskStatusChartRef.value)
    taskStatusChart.setOption({
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          type: 'shadow'
        }
      },
      xAxis: {
        type: 'category',
        data: []
      },
      yAxis: {
        type: 'value'
      },
      series: [{
        data: [],
        type: 'bar'
      }]
    })
  }

  // 系统性能图表
  if (performanceChartRef.value) {
    performanceChart = echarts.init(performanceChartRef.value)
    performanceChart.setOption({
      tooltip: {
        trigger: 'axis'
      },
      legend: {
        data: ['CPU使用率', '内存使用率', '磁盘使用率', '网络使用率']
      },
      xAxis: {
        type: 'category',
        data: ['性能指标']
      },
      yAxis: {
        type: 'value',
        max: 100
      },
      series: [
        {
          name: 'CPU使用率',
          type: 'bar',
          data: [0]
        },
        {
          name: '内存使用率',
          type: 'bar',
          data: [0]
        },
        {
          name: '磁盘使用率',
          type: 'bar',
          data: [0]
        },
        {
          name: '网络使用率',
          type: 'bar',
          data: [0]
        }
      ]
    })
  }
}

// 刷新数据
const refreshData = async () => {
  try {
    // 获取日志汇总
    const summaryData = await getLogSummary(7)
    logSummary.value = summaryData

    // 获取任务状态统计
    const taskStatusData = await getTaskStatusStatistics()
    updateTaskStatusChart(taskStatusData)

    // 获取系统性能
    const performanceData = await getPerformance()
    updatePerformanceChart(performanceData)

    // 更新趋势图表
    updateTrendChart(summaryData.taskTrend)

    // 更新分布图表
    updateDistributionChart(summaryData.anomalyDistribution)

    const historyData = await getHistory(1, 10)
    recentTasks.value = historyData.records || historyData.list || []

    scheduleChartResize()
  } catch (error) {
    console.error('获取数据失败:', error)
    ElMessage.error('仪表盘数据加载失败: ' + (error?.message || '请检查登录与网络'))
  }
}

// 更新趋势图表
const updateTrendChart = (taskTrend) => {
  if (!trendChart) return
  const { labels, values } = buildTrendSeries(taskTrend, TREND_DAYS)
  const hasData = values.some((v) => v > 0)

  trendChart.setOption(
    {
      title: hasData
        ? undefined
        : {
            text: '近 7 日暂无新分析任务',
            left: 'center',
            top: 'middle',
            textStyle: { color: '#909399', fontSize: 14, fontWeight: 'normal' }
          },
      grid: { left: 48, right: 24, top: 40, bottom: 32 },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: labels,
        boundaryGap: false,
        axisLabel: { rotate: labels.length > 5 ? 30 : 0 }
      },
      yAxis: { type: 'value', minInterval: 1 },
      series: [
        {
          name: '分析任务数',
          type: 'line',
          smooth: true,
          data: values,
          areaStyle: { opacity: 0.12 },
          itemStyle: { color: '#0d9488' },
          lineStyle: { width: 2 }
        }
      ]
    },
    true
  )
}

// 更新分布图表
const updateDistributionChart = (anomalyDistribution) => {
  if (!distributionChart) return
  const list = Array.isArray(anomalyDistribution) ? anomalyDistribution : []
  const data = list
    .map((item) => ({
      name: String(pickField(item, 'type', 'name', 'severity') ?? 'UNKNOWN'),
      value: Number(pickField(item, 'count', 'value', 'cnt') || 0)
    }))
    .filter((d) => d.value > 0)

  if (!data.length) {
    distributionChart.setOption(
      {
        title: {
          text: '暂无异常日志（近 7 日）',
          subtext: '完成证据分析且标记为异常后此处显示级别分布',
          left: 'center',
          top: 'center',
          textStyle: { color: '#909399', fontSize: 14, fontWeight: 'normal' },
          subtextStyle: { color: '#c0c4cc', fontSize: 12 }
        },
        legend: { show: false },
        series: [{ type: 'pie', radius: ['40%', '62%'], data: [] }]
      },
      true
    )
    return
  }

  distributionChart.setOption(
    {
      title: { show: false },
      legend: { orient: 'vertical', left: 'left', top: 'middle' },
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      color: ['#f56c6c', '#e6a23c', '#409eff', '#909399', '#67c23a'],
      series: [
        {
          type: 'pie',
          radius: ['38%', '62%'],
          center: ['58%', '50%'],
          data,
          emphasis: {
            itemStyle: {
              shadowBlur: 10,
              shadowOffsetX: 0,
              shadowColor: 'rgba(0, 0, 0, 0.2)'
            }
          },
          label: { formatter: '{b}\n{c}' }
        }
      ]
    },
    true
  )
}

// 更新任务状态图表
const updateTaskStatusChart = (taskStatusData) => {
  if (!taskStatusChart || !taskStatusData || !taskStatusData.statusCount) {
    return
  }
  const statuses = Object.keys(taskStatusData.statusCount)
  const counts = statuses.map(status => taskStatusData.statusCount[status])

  taskStatusChart.setOption(
    {
      grid: { left: 48, right: 24, top: 24, bottom: 32 },
      tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: statuses },
      yAxis: { type: 'value', minInterval: 1 },
      series: [
        {
          type: 'bar',
          data: counts,
          itemStyle: { color: '#0d9488' },
          barMaxWidth: 48
        }
      ]
    },
    true
  )
}

// 更新性能图表
const updatePerformanceChart = (performanceData) => {
  if (performanceChart && performanceData) {
    performanceChart.setOption(
      {
        grid: { left: 48, right: 24, top: 40, bottom: 32 },
        tooltip: { trigger: 'axis' },
        legend: { data: ['CPU使用率', '内存使用率', '磁盘使用率', '网络使用率'] },
        xAxis: { type: 'category', data: ['当前'] },
        yAxis: { type: 'value', max: 100 },
        series: [
          { name: 'CPU使用率', type: 'bar', data: [performanceData.cpuUsage || 0] },
          { name: '内存使用率', type: 'bar', data: [performanceData.memoryUsage || 0] },
          { name: '磁盘使用率', type: 'bar', data: [performanceData.diskUsage || 0] },
          { name: '网络使用率', type: 'bar', data: [performanceData.networkUsage || 0] }
        ]
      },
      true
    )
  }
}

// 获取状态类型
const getStatusType = (status) => {
  const statusMap = {
    'COMPLETED': 'success',
    'PROCESSING': 'warning',
    'FAILED': 'danger',
    'PENDING': 'info'
  }
  return statusMap[status] || 'info'
}

const goToOpsAgent = () => {
  window.dispatchEvent(
    new CustomEvent('ops-navigate-agent', {
      detail: {
        message: '结合仪表盘异常趋势，分析最近系统日志并扫描 /tmp 与 /var/log 的磁盘占用热点（预览清理策略）'
      }
    })
  )
}

const opsAgentForFailedTask = (row) => {
  dispatchOpsAgentPrefill(agentPrefillFailedTask(row))
}

const opsAgentDeepDiveTask = (row) => {
  dispatchOpsAgentPrefill(agentPrefillHighAnomaly(row))
}

// 查看任务详情
const viewTask = (taskId) => {
  // 触发查看任务事件，跳转到证据分析页面查看详情
  console.log('查看任务详情:', taskId)
  emit('view-task', taskId)
}

// 响应式调整图表大小
const handleResize = () => {
  trendChart?.resize()
  distributionChart?.resize()
  taskStatusChart?.resize()
  performanceChart?.resize()
}

// 生命周期钩子
onMounted(async () => {
  await nextTick()
  initCharts()
  await refreshData()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  distributionChart?.dispose()
  taskStatusChart?.dispose()
  performanceChart?.dispose()
})
</script>

<style scoped>
.log-analysis-dashboard {
  padding: 0;
}

.dashboard-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.stats-row {
  display: flex;
  gap: 20px;
  margin-bottom: 20px;
}

.stat-card {
  flex: 1;
  min-width: 150px;
}

.stat-item {
  text-align: center;
}

.stat-label {
  font-size: 12px;
  color: var(--ops-text-subtle);
  margin-bottom: 6px;
  font-weight: 500;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--ops-text);
  font-variant-numeric: tabular-nums;
}

.charts-row {
  display: flex;
  gap: 20px;
  margin-bottom: 20px;
}

.chart-card {
  flex: 1;
  min-height: 300px;
}

.chart-container {
  width: 100%;
  height: 280px;
  min-height: 280px;
  background: transparent;
}

.echarts-host {
  position: relative;
  z-index: 1;
}

.table-card {
  margin-top: 20px;
}

.task-id {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
}

@media (max-width: 1200px) {
  .stats-row {
    flex-wrap: wrap;
  }

  .stat-card {
    flex: 1 1 calc(50% - 10px);
  }

  .charts-row {
    flex-direction: column;
  }
}
</style>
