<template>
  <div class="ops-page log-analysis-page" :class="{ 'log-analysis-page--fill': isUploadIdle }">
      <!-- 1. 初始上传状态 -->
      <transition name="el-fade-in-linear">
        <div v-if="!reportData && taskStatus.status !== 'PROCESSING' && taskStatus.status !== 'FAILED'" class="upload-section">
          <OpsPageHeader
            title="观测证据分析"
            subtitle="导入日志证据，自动识别异常并生成可交给 agent 继续推理的诊断报告"
          />

          <el-card shadow="never" class="upload-panel">
            <el-row :gutter="20" align="stretch" class="upload-panel__row">
              <el-col :xs="24" :lg="15">
                <div
                  class="upload-box"
                  role="button"
                  tabindex="0"
                  @click="triggerUpload"
                  @keydown.enter.prevent="triggerUpload"
                  @keydown.space.prevent="triggerUpload"
                  @drop.prevent="handleDrop"
                  @dragover.prevent
                >
                  <input type="file" ref="fileInput" class="hidden-input" @change="handleFileSelect">
                  <el-icon class="upload-icon"><upload-filled /></el-icon>
                  <div class="upload-hint">点击或拖拽日志证据到此处</div>
                  <div class="upload-sub">单文件证据输入，分析完成后可导出 CSV / HTML 报告</div>
                </div>
              </el-col>
              <el-col :xs="24" :lg="9">
                <div class="upload-guide">
                  <div class="upload-guide__block">
                    <div class="upload-guide__title">支持格式</div>
                    <div class="upload-guide__tags">
                      <el-tag size="small" effect="plain">.log</el-tag>
                      <el-tag size="small" effect="plain">.txt</el-tag>
                      <el-tag size="small" effect="plain">syslog</el-tag>
                      <el-tag size="small" effect="plain">Windows 事件</el-tag>
                      <el-tag size="small" effect="plain">应用日志</el-tag>
                    </div>
                  </div>
                  <div class="upload-guide__block">
                    <div class="upload-guide__title">证据流程</div>
                    <ol class="upload-guide__steps">
                      <li>Drain 模板挖掘与异常检测</li>
                      <li>生成健康度与风险分布报告</li>
                      <li>可把单条异常带入 agent 继续诊断</li>
                    </ol>
                  </div>
                  <div class="upload-guide__tip">
                    真实环境建议先脱敏；敏感字段会在展示层脱敏处理。
                  </div>
                </div>
              </el-col>
            </el-row>
          </el-card>
        </div>
      </transition>

      <!-- 2. 分析进度加载状态 -->
      <transition name="el-zoom-in-center">
        <div v-if="taskStatus.status === 'PROCESSING'" class="processing-section">
          <div class="processing-spinner"></div>
          <h2 class="scan-text">正在分析日志</h2>
          <div class="current-step">{{ taskStatus.currentStep }}</div>
          <el-progress 
            :percentage="taskStatus.progress" 
            :stroke-width="12" 
            :show-text="false"
            status="success"
            class="scan-progress"
          />
          <div class="scan-metrics">
            <span>进度 {{ taskStatus.progress }}%</span>
          </div>
          <div class="task-controls">
            <el-button 
              type="danger" 
              size="large" 
              @click="handleCancelTask"
              :icon="CircleClose"
            >
              取消任务
            </el-button>
          </div>
        </div>
      </transition>

      <!-- 分析失败：跳转工作台对话 -->
      <transition name="el-fade-in-linear">
        <div v-if="taskStatus.status === 'FAILED' && !reportData" class="failure-section">
          <el-result icon="error" title="诊断失败" :sub-title="taskStatus.errorMsg || '处理异常，可在对话中继续排查'">
            <template #extra>
              <el-button type="primary" @click="goOpsAgentAfterFailure">去对话排查</el-button>
              <el-button @click="resetTask">重新上传</el-button>
            </template>
          </el-result>
        </div>
      </transition>

      <!-- 3. 分析报告仪表盘 -->
      <transition name="el-zoom-in-bottom">
        <div v-if="reportData && taskStatus.status === 'COMPLETED'" class="dashboard-container">
          <!-- 顶部核心指标栏 -->
          <div class="kpi-board">
            <div class="kpi-card health-score">
              <div class="gauge-chart" id="healthGauge"></div>
              <div class="kpi-info">
                <div class="kpi-title">系统健康度</div>
                <div class="kpi-desc">{{ healthStatusText }}</div>
              </div>
            </div>
            
            <div class="kpi-card" v-for="item in stats" :key="item.label">
              <div class="stat-icon-wrapper" :style="{ background: item.bg }">
                 <el-icon :size="24" :color="item.color"><component :is="item.icon" /></el-icon>
              </div>
              <div class="stat-content">
                <div class="kpi-value" :style="{ color: item.color }">{{ item.value }}</div>
                <div class="kpi-label">{{ item.label }}</div>
              </div>
            </div>
            
            <div class="kpi-card action-card">
              <el-button type="primary" size="large" @click="resetTask" :icon="Refresh">上传新日志</el-button>
              <el-button
                v-if="showAgentDeepDiveOnReport"
                type="success"
                size="large"
                @click="goOpsAgentDeepDiveFromReport"
              >继续分析</el-button>
              <el-button type="warning" size="large" @click="handleGlobalDiagnosis" :icon="Cpu">生成诊断报告</el-button>
              
              <el-dropdown @command="handleDownload">
                <el-button plain size="large" :icon="Download">
                  导出报告<el-icon class="el-icon--right"><arrow-down /></el-icon>
                </el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="csv">CSV 数据表格</el-dropdown-item>
                    <el-dropdown-item command="html">可视化 HTML 报告</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>

          <el-row :gutter="24" class="content-row">
            <!-- 左侧：日志详情表 -->
            <el-col :span="16">
              <el-card class="detail-card" shadow="never">
                <template #header>
                  <div class="card-header">
                    <span class="title">关键异常事件</span>
                    <el-tag type="danger" effect="plain" size="small">{{ anomalyLogs.length }} 个异常项</el-tag>
                  </div>
                </template>
                <el-table :data="anomalyLogs" stripe style="width: 100%" height="500px">
                  <el-table-column label="事件 ID" width="100">
                    <template #default="scope">
                       <span class="event-id-tag" @click="openDetail(scope.row)">{{ scope.$index + 1 }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="severity" label="级别" width="90">
                    <template #default="scope">
                      <el-tag :type="getSeverityType(scope.row.severity)" size="small" effect="dark">{{ scope.row.severity }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="protocol" label="来源/组件" width="150" show-overflow-tooltip />
                  <el-table-column prop="desensitizedLog" label="消息内容" show-overflow-tooltip />
                  <el-table-column prop="logTime" label="时间" width="160" sortable />
                  <el-table-column label="AI 诊断" width="120" fixed="right">
                    <template #default="scope">
                      <el-button 
                        type="primary" 
                        link 
                        :icon="Cpu" 
                        @click.stop="handleSingleRowDiagnosis(scope.row)"
                      >诊断</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>

            <!-- 右侧：分布图与建议 -->
            <el-col :span="8">
              <el-card class="chart-card" shadow="never">
                <template #header><span class="title">风险等级分布</span></template>
                <div class="severity-chart-wrapper">
                  <div id="severityChart" style="height: 220px; flex: 1.5;"></div>
                  <div class="severity-legend">
                    <div class="legend-item" v-for="item in legendData" :key="item.name">
                      <span class="dot" :style="{ backgroundColor: item.color }"></span>
                      <span class="label">{{ item.name }}</span>
                    </div>
                  </div>
                </div>
              </el-card>
              
              <el-card class="advice-card" shadow="never">
                <template #header><span class="title">系统优化建议</span></template>
                <div class="advice-list">
                  <div class="advice-item" v-for="(adv, index) in adviceList" :key="index">
                    <el-icon :color="adv.type === 'warn' ? '#E6A23C' : '#409EFF'"><warning v-if="adv.type==='warn'" /><circle-check v-else /></el-icon>
                    <span>{{ adv.text }}</span>
                  </div>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </div>
      </transition>

    <!-- 详情弹窗 -->
    <el-dialog v-model="logDetailDialogVisible" title="日志详情" width="800px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="日志时间">{{ currentLogDetail.logTime }}</el-descriptions-item>
        <el-descriptions-item label="级别">
          <el-tag :type="getSeverityType(currentLogDetail.severity || '')" size="small">{{ currentLogDetail.severity }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="来源/组件">{{ currentLogDetail.protocol }}</el-descriptions-item>
        <el-descriptions-item label="事件ID (PID)">{{ currentLogDetail.pid }}</el-descriptions-item>
        <el-descriptions-item label="模板ID">{{ currentLogDetail.templateId }}</el-descriptions-item>
        <el-descriptions-item label="异常得分">{{ currentLogDetail.anomalyScore }}</el-descriptions-item>
      </el-descriptions>

      <div style="margin-top: 20px;">
        <h4>消息内容</h4>
        <div class="log-content-box">{{ currentLogDetail.desensitizedLog }}</div>
      </div>

      <div v-if="currentLogDetail.anomalyReasons && currentLogDetail.anomalyReasons.length" style="margin-top: 20px;">
        <h4 style="color: #F56C6C;">异常原因</h4>
        <ul style="color: #F56C6C;">
          <li v-for="(reason, idx) in currentLogDetail.anomalyReasons" :key="idx">{{ reason }}</li>
        </ul>
      </div>

      <div v-if="currentLogDetail.stackTrace && currentLogDetail.stackTrace !== '无异常栈'" style="margin-top: 20px;">
        <h4>堆栈信息</h4>
        <div class="stack-trace-box">{{ currentLogDetail.stackTrace }}</div>
      </div>
    </el-dialog>

    <!-- AI 诊断弹窗 -->
    <el-dialog v-model="aiDialogVisible" title="诊断结果" width="700px" center custom-class="ai-dialog">
      <div class="ai-content">
        <div class="ai-header">
          <div class="ai-avatar"><el-icon :size="28"><Service /></el-icon></div>
          <div class="ai-bubble">
            <p v-if="aiThinking">正在分析异常日志并生成诊断建议…</p>
            <p v-else>诊断结果如下：</p>
          </div>
        </div>
        
        <div class="analysis-result" v-if="aiThinking">
           <el-skeleton :rows="5" animated />
        </div>
        <div class="analysis-result fade-in" v-else>
           <!-- 使用 v-html 渲染 Markdown -->
           <div class="ai-markdown-body" v-html="renderedAiDiagnosis"></div>
        </div>
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="aiDialogVisible = false">关闭</el-button>
          <el-button type="primary" @click="copySolution">复制诊断报告</el-button>
        </span>
      </template>
    </el-dialog>
    
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, onUnmounted, defineExpose } from 'vue'
import { UploadFilled, WarningFilled, Warning, CircleCheck, Refresh, Download, Cpu, Timer, CircleCheckFilled, ArrowDown, CircleClose, Service } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { uploadLog, uploadLogs, getTaskStatus, getReport, performDiagnosis, cancelTask, downloadReport, getApiBaseUrl } from '../api'
import { ElMessage } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { appendSsePayloadFromChunk, flushSseBuffer } from '../utils/sseStream'
import {
  dispatchOpsAgentPrefill,
  agentPrefillEngineFailure,
  agentPrefillHighAnomaly,
  OPS_AGENT_ANOMALY_THRESHOLD
} from '../utils/opsAgentNavigate'
import OpsPageHeader from './OpsPageHeader.vue'

// Props to accept taskId from parent (history navigation)
const props = defineProps(['initialTaskId'])

// Markdown parser
const md = new MarkdownIt()

// --- State ---
const fileInput = ref(null)
const taskId = ref('')
const reportData = ref(null)
const anomalyLogs = ref([])
const stats = ref([])
const adviceList = ref([])

const isUploadIdle = computed(() => (
  !reportData.value
  && taskStatus.status !== 'PROCESSING'
  && taskStatus.status !== 'FAILED'
))

const legendData = [
  { name: '致命故障', color: '#B71C1C' },
  { name: '运行错误', color: '#FF5722' },
  { name: '风险警告', color: '#FFC107' },
  { name: '系统信息', color: '#1E88E5' },
  { name: '未知等级', color: '#9E9E9E' }
]

// Log Detail Dialog State
const logDetailDialogVisible = ref(false)
const currentLogDetail = ref({})

// AI Dialog State
const aiDialogVisible = ref(false)
const aiThinking = ref(false)
const currentEventId = ref('')
const aiResult = reactive({ summary: '', reasons: [], solution: '' })
const aiResultText = ref('') // Store the global diagnosis text

const renderedAiDiagnosis = computed(() => {
  return md.render(aiResultText.value)
})

const taskStatus = reactive({
  status: 'IDLE', // IDLE, PROCESSING, COMPLETED, FAILED
  progress: 0,
  currentStep: '',
  errorMsg: ''
})

let timer = null
let pollFailCount = 0
let lastProgress = -1
let stallTicks = 0

// Initialize with prop if available
onMounted(() => {
    if (props.initialTaskId) {
        loadTask(props.initialTaskId)
    }
})

onUnmounted(() => {
  clearPollTimer()
  abortDiagnosis()
  disposeCharts()
})

const gaugeChartRef = ref(null)
const pieChartRef = ref(null)
let diagnosisAbort = null

function disposeCharts () {
  try {
    gaugeChartRef.value?.dispose?.()
  } catch { /* noop */ }
  try {
    pieChartRef.value?.dispose?.()
  } catch { /* noop */ }
  gaugeChartRef.value = null
  pieChartRef.value = null
}

function abortDiagnosis () {
  if (diagnosisAbort) {
    try { diagnosisAbort.abort() } catch { /* noop */ }
    diagnosisAbort = null
  }
}

function clearPollTimer () {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function startStatusPolling () {
  clearPollTimer()
  pollFailCount = 0
  lastProgress = -1
  stallTicks = 0
  timer = setInterval(async () => {
    try {
      const res = await getTaskStatus(taskId.value)
      pollFailCount = 0
      taskStatus.progress = res.progress
      taskStatus.currentStep = res.currentStep
      taskStatus.status = res.status

      if (res.status === 'PROCESSING') {
        if (res.progress === lastProgress) {
          stallTicks++
          if (stallTicks >= 30 && stallTicks % 15 === 0) {
            taskStatus.currentStep = `${res.currentStep || '处理中'}（仍在运行，请稍候…）`
          }
        } else {
          stallTicks = 0
          lastProgress = res.progress
        }
      }

      if (res.status === 'COMPLETED') {
        clearPollTimer()
        fetchReport()
      } else if (res.status === 'FAILED') {
        clearPollTimer()
        taskStatus.errorMsg = res.errorMsg || '分析失败'
        ElMessage.error(taskStatus.errorMsg)
      } else if (res.status === 'PAUSED' || res.status === 'CANCELLED') {
        clearPollTimer()
        if (res.status === 'CANCELLED') {
          taskStatus.errorMsg = res.errorMsg || '任务已取消'
          ElMessage.info(taskStatus.errorMsg)
        }
      }
    } catch (err) {
      pollFailCount++
      if (pollFailCount >= 3) {
        clearPollTimer()
        taskStatus.status = 'FAILED'
        taskStatus.errorMsg = err?.message || '任务状态查询失败，请稍后到任务记录查看'
        ElMessage.error(taskStatus.errorMsg)
      }
    }
  }, 1000)
}

// Expose a method to load specific task
const loadTask = async (id) => {
    taskId.value = id
    clearPollTimer()
    try {
        const statusRes = await getTaskStatus(id)
        const status = statusRes?.status || ''
        taskStatus.progress = statusRes?.progress ?? 0
        taskStatus.currentStep = statusRes?.currentStep || ''
        taskStatus.errorMsg = statusRes?.errorMsg || ''

        if (status === 'PROCESSING' || status === 'PENDING' || status === 'PAUSED') {
          taskStatus.status = status === 'PENDING' ? 'PROCESSING' : status
          reportData.value = null
          anomalyLogs.value = []
          if (status !== 'PAUSED') {
            startStatusPolling()
          }
          return
        }
        if (status === 'FAILED' || status === 'CANCELLED') {
          taskStatus.status = status
          taskStatus.errorMsg = statusRes?.errorMsg || (status === 'CANCELLED' ? '任务已取消' : '分析失败')
          reportData.value = null
          return
        }

        taskStatus.status = 'COMPLETED'
        await fetchReport()
    } catch (error) {
        console.error('加载任务失败:', error)
        ElMessage.error('加载任务失败：' + (error.message || '未找到相关任务'))
        taskStatus.status = 'IDLE'
        reportData.value = null
    }
}


// --- Log Detail Logic ---
const openDetail = (row) => {
  currentLogDetail.value = row
  logDetailDialogVisible.value = true
}

const handleSingleRowDiagnosis = async (row) => {
    aiDialogVisible.value = true
    aiThinking.value = true
    aiResultText.value = ''
    
    let payload
    try {
        payload = JSON.parse(JSON.stringify(row))
    } catch (e) {
        payload = row
    }

    try {
        const response = await fetch(`${getApiBaseUrl()}/log/quick-diagnose`, {
            method: 'POST',
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: JSON.stringify(payload)
        })

        const text = await response.text()
        if (!response.ok) {
            let msg = `请求失败 (${response.status})`
            try {
                const errJson = JSON.parse(text)
                if (errJson.message) msg = errJson.message
            } catch {
                if (text) msg = text.slice(0, 200)
            }
            aiResultText.value = '诊断失败: ' + msg
            return
        }

        const resData = JSON.parse(text)
        if (resData.code === 200) {
             aiResultText.value = resData.data
        } else {
             aiResultText.value = '诊断失败: ' + (resData.message || '未知错误')
        }
    } catch (error) {
        aiResultText.value = '诊断服务暂时不可用，请稍后再试。'
        console.error('Diagnosis error:', error)
    } finally {
        aiThinking.value = false
    }
}

// --- Global AI Diagnosis ---
const handleGlobalDiagnosis = async () => {
    if (!taskId.value) {
        ElMessage.warning('请先上传并分析日志')
        return
    }
    
    // Check if result is already cached in reportData
    if (reportData.value && reportData.value.aiDiagnosis) {
        aiResultText.value = reportData.value.aiDiagnosis
        aiDialogVisible.value = true
        return
    }

    // Check task status before calling AI diagnosis
    try {
        const taskStatus = await getTaskStatus(taskId.value)
        if (taskStatus.status !== 'COMPLETED') {
            ElMessage.warning('请等待证据分析完成后再进行 AI 诊断')
            return
        }
    } catch (error) {
        ElMessage.error('获取任务状态失败，请稍后再试')
        return
    }

    aiDialogVisible.value = true
    aiThinking.value = true
    aiResultText.value = ''

    abortDiagnosis()
    diagnosisAbort = new AbortController()

    // 与「统一 AI 助手」一致：用 fetch + ReadableStream 读流。浏览器 EventSource 对 Spring SSE 兼容性差，易导致无输出或立即断开。
    try {
        const response = await fetch(`${getApiBaseUrl()}/log/diagnose/stream/${taskId.value}`, {
            method: 'GET',
            credentials: 'include',
            signal: diagnosisAbort.signal
        })
        if (!response.ok) {
            const errText = await response.text().catch(() => '')
            let msg = `请求失败 (${response.status})`
            try {
                const j = JSON.parse(errText)
                if (j.message) msg = j.message
            } catch {
                if (errText) msg = errText.slice(0, 300)
            }
            aiResultText.value = '诊断失败: ' + msg
            return
        }
        const reader = response.body?.getReader()
        if (!reader) {
            aiResultText.value = '诊断失败: 服务端未返回流式数据'
            return
        }
        const decoder = new TextDecoder()
        let sseBuf = ''
        while (true) {
            const { done, value } = await reader.read()
            if (done) break
            aiThinking.value = false
            const chunk = decoder.decode(value, { stream: true })
            sseBuf = appendSsePayloadFromChunk(sseBuf, chunk, (payload) => {
                aiResultText.value += payload
            })
        }
        sseBuf = flushSseBuffer(sseBuf, (payload) => {
            aiResultText.value += payload
        })
        if (reportData.value) {
            reportData.value.aiDiagnosis = aiResultText.value
        }
    } catch (error) {
        if (error?.name === 'AbortError') {
          return
        }
        console.error('Global diagnosis error:', error)
        if (aiResultText.value.length === 0) {
            aiResultText.value = '诊断服务连接中断，请确认任务存在且AI服务可用。'
        } else if (reportData.value) {
            reportData.value.aiDiagnosis = aiResultText.value
        }
    } finally {
        aiThinking.value = false
        diagnosisAbort = null
    }
}

// --- Upload Logic ---
const triggerUpload = () => fileInput.value.click()
const handleFileSelect = (e) => processFiles(e.target.files)
const handleDrop = (e) => processFiles(e.dataTransfer.files)

const processFiles = async (files) => {
  if (!files || files.length === 0) return
  
  // 只处理第一个文件，回归到单文件上传
  const file = files[0]
  if (!file) {
    ElMessage.warning('请选择有效的日志文件')
    return
  }
  
  try {
    taskStatus.status = 'PROCESSING'
    taskStatus.progress = 0
    taskStatus.currentStep = '正在建立安全连接...'
    taskStatus.errorMsg = ''
    
    // 单文件上传
    const formData = new FormData()
    formData.append('file', file)
    const id = await uploadLog(formData)
    taskId.value = id
    startStatusPolling()

  } catch (err) {
    taskStatus.status = 'FAILED'
    taskStatus.errorMsg = err.message || '上传失败'
    ElMessage.error('上传失败：' + err.message)
  }
}

// --- Report Logic ---
const fetchReport = async () => {
  try {
    const data = await getReport(taskId.value)
    const result = data.result || []
    const s = data.summary || {}

    anomalyLogs.value = result.filter(i => {
      const sev = String(i?.severity || '')
      return sev.includes('ERROR') || sev.includes('FATAL') || i.anomaly
    }).slice(0, 100)

    const sev = s?.severityCounts || {}
    const fatalCount = Number(sev.FATAL ?? sev.fatal ?? 0) || 0
    const errorCount = Number(sev.ERROR ?? sev.error ?? 0) || 0
    const warnCount = Number(sev.WARN ?? sev.WARNING ?? sev.warn ?? 0) || 0
    const anomalyCount = Number(s?.anomalyCount ?? 0) || 0
    const healthScore = Math.max(
      0,
      Math.min(100, 100 - fatalCount * 20 - errorCount * 8 - warnCount * 2 - Math.max(0, anomalyCount - fatalCount - errorCount))
    )
    let threatLabel = '无'
    let threatColor = '#67C23A'
    let threatBg = '#f0f9eb'
    if (fatalCount > 0) {
      threatLabel = `致命 ${fatalCount}`
      threatColor = '#F56C6C'
      threatBg = '#fef0f0'
    } else if (errorCount > 0) {
      threatLabel = `错误 ${errorCount}`
      threatColor = '#E6A23C'
      threatBg = '#fdf6ec'
    } else if (anomalyCount > 0) {
      threatLabel = `异常 ${anomalyCount}`
      threatColor = '#E6A23C'
      threatBg = '#fdf6ec'
    }

    stats.value = [
      { label: '扫描耗时', value: ((s?.costTime || 0) / 1000).toFixed(2) + 's', color: '#606266', icon: Timer, bg: '#f2f6fc' },
      { label: '风险条目', value: anomalyCount, color: '#F56C6C', icon: WarningFilled, bg: '#fef0f0' },
      { label: '安全威胁', value: threatLabel, color: threatColor, icon: CircleCheckFilled, bg: threatBg }
    ]

    reportData.value = {
      ...data,
      result: anomalyLogs.value
    }
    taskStatus.status = 'COMPLETED'

    if (s.lineCapApplied) {
      ElMessage.warning(
        `文件过大，分析已截断至上限（另跳过约 ${s.linesSkipped || 0} 行）；报告为异常抽样。`
      )
    } else if (s.resultTruncated) {
      ElMessage.info(
        `报告仅返回异常抽样 ${s.resultReturned || anomalyLogs.value.length} 条（明细共 ${s.detailTotal || '—'} 条）。`
      )
    }

    generateAdvice(anomalyLogs.value)

    nextTick(() => {
      initCharts(result, healthScore, s.severityCounts)
    })
  } catch (error) {
    console.error('获取报告失败:', error)
    taskStatus.status = 'FAILED'
    taskStatus.errorMsg = error?.message || '获取分析报告失败'
    ElMessage.error(taskStatus.errorMsg)
  }
}

// --- Health Score Logic ---
const healthStatusText = computed(() => {
  const score = stats.value[1] ? 100 - (stats.value[1].value * 2) : 100
  if(score >= 90) return '系统状态极佳'
  if(score >= 70) return '系统状态良好'
  return '系统存在隐患'
})

const showAgentDeepDiveOnReport = computed(() => {
  const s = reportData.value?.summary
  if (!s) return false
  const n = Number(s.anomalyCount)
  return Number.isFinite(n) && n >= OPS_AGENT_ANOMALY_THRESHOLD
})

const goOpsAgentDeepDiveFromReport = () => {
  const d = reportData.value
  if (!d?.summary) return
  dispatchOpsAgentPrefill(
    agentPrefillHighAnomaly({
      taskId: d.taskId || taskId.value,
      fileName: d.fileName,
      status: 'COMPLETED',
      summary: d.summary
    })
  )
}

const copySolution = async () => {
  try {
    // 使用浏览器的 Clipboard API 复制诊断报告
    await navigator.clipboard.writeText(aiResultText.value)
    ElMessage.success('诊断报告已成功复制到剪贴板')
    aiDialogVisible.value = false
  } catch (err) {
    console.error('复制到剪贴板失败:', err)
    ElMessage.error('复制到剪贴板失败，请手动复制')
  }
}

const generateAdvice = (logs) => {
    adviceList.value = []
    if (logs.length > 0) {
        adviceList.value.push({ type: 'warn', text: `发现 ${logs.length} 个异常事件，建议优先处理高危项。` })
        adviceList.value.push({ type: 'info', text: '建议定期清理 C:\\Windows\\Temp 目录。' })
    } else {
        adviceList.value.push({ type: 'success', text: '暂未发现明显系统风险。' })
    }
}

// --- Visuals ---
const initCharts = (result, healthScore, severityCounts) => {
  const chartData = Array.isArray(result) ? result : []
  disposeCharts()

  const gaugeEl = document.getElementById('healthGauge')
  const pieEl = document.getElementById('severityChart')
  if (!gaugeEl || !pieEl) return

  const gaugeChart = echarts.init(gaugeEl)
  gaugeChartRef.value = gaugeChart
  gaugeChart.setOption({
    series: [{
      type: 'gauge',
      startAngle: 90, endAngle: -270,
      pointer: { show: false },
      progress: {
        show: true,
        overlap: false,
        roundCap: true,
        clip: false
      },
      axisLine: { lineStyle: { width: 10, color: [[1, '#E6EBF8']] } },
      splitLine: { show: false },
      axisTick: { show: false },
      axisLabel: { show: false },
      data: [{
        value: healthScore,
        itemStyle: { color: healthScore > 80 ? '#67C23A' : '#E6A23C' }
      }],
      detail: {
        width: 50, height: 14,
        fontSize: 24, color: '#303133',
        formatter: '{value}',
        offsetCenter: ['0%', '0%']
      }
    }]
  })

  const pieChart = echarts.init(pieEl)
  pieChartRef.value = pieChart

  const severityMap = {
    'FATAL_LEVEL': { name: '致命故障', color: '#B71C1C' },
    'ERROR_LEVEL': { name: '运行错误', color: '#FF5722' },
    'WARNING_LEVEL': { name: '风险警告', color: '#FFC107' },
    'INFO_LEVEL': { name: '系统信息', color: '#1E88E5' },
    'DEBUG_LEVEL': { name: '调试跟踪', color: '#4CAF50' },
    'UNKNOWN_LEVEL': { name: '未知等级', color: '#9E9E9E' }
  }

  const counts = {}
  if (severityCounts && typeof severityCounts === 'object') {
    Object.keys(severityCounts).forEach(k => {
      counts[k] = Number(severityCounts[k]) || 0
    })
  } else {
    const sample = chartData.length > 5000
      ? chartData.filter((_, i) => i % Math.ceil(chartData.length / 5000) === 0)
      : chartData
    sample.forEach(r => {
      const key = r.severity
      counts[key] = (counts[key] || 0) + 1
    })
  }

  const data = Object.keys(counts).map(k => {
    const config = severityMap[k] || { name: k, color: '#909399' }
    return {
      name: config.name,
      value: counts[k],
      itemStyle: { color: config.color }
    }
  })

  pieChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} ({d}%)'
    },
    series: [{
      type: 'pie',
      radius: ['50%', '70%'],
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 6,
        borderColor: '#fff',
        borderWidth: 2
      },
      label: { show: false },
      data: data
    }]
  })
}

const getSeverityType = (sev) => {
  const s = String(sev || '')
  if (s.includes('FATAL')) return 'danger'
  if (s.includes('ERROR')) return 'danger'
  if (s.includes('WARN')) return 'warning'
  return 'info'
}

const resetTask = () => {
  disposeCharts()
  abortDiagnosis()
  reportData.value = null
  taskStatus.status = 'IDLE'
  taskStatus.progress = 0
  taskStatus.currentStep = ''
  taskStatus.errorMsg = ''
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

const goOpsAgentAfterFailure = () => {
  dispatchOpsAgentPrefill(agentPrefillEngineFailure(taskId.value, taskStatus.errorMsg))
}


// 取消任务方法
const handleCancelTask = async () => {
  if (!taskId.value) return
  
  try {
    // 先清除定时器，避免继续轮询任务状态
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    
    await cancelTask(taskId.value)
    // 调用后端API成功后，重置前端状态
    resetTask()
    ElMessage.success('任务已取消')
  } catch (error) {
    ElMessage.error('取消任务失败: ' + error.message)
    console.error('取消任务失败:', error)
  }
}

const handleDownload = async (type) => {
  try {
    if (!taskId.value) {
      ElMessage.error('缺少任务 ID，无法导出')
      return
    }
    const blob = await downloadReport(taskId.value, type)
    const fileName = type === 'csv' ? 'log_analysis_result.csv' : 'log_analysis_report.html'
    const url = window.URL.createObjectURL(new Blob([blob]))
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', fileName)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success(`已下载 ${fileName}`)
  } catch (error) {
    console.error('下载报告失败:', error)
    ElMessage.error('下载报告失败，请先确认已登录且报告已生成')
  }
}

// 暴露所有需要给父组件访问的方法
defineExpose({
  loadTask
})
</script>

<style scoped>
.log-analysis-page--fill {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  /* 抵消 shell-content 内边距，占满主内容区 */
  margin: -16px -20px -20px;
  padding: 16px 20px 20px;
  min-height: calc(100vh - var(--ops-header-h, 52px));
}

/* 上传区域 */
.upload-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  animation: fadeInUp 0.8s ease;
}

.upload-section :deep(.ops-page-header) {
  flex-shrink: 0;
  margin-bottom: 16px;
}

.upload-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--ops-border, #e2e8f0);
  border-radius: var(--ops-radius, 12px);
  background: var(--ops-panel, #fff);
}

.upload-panel :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 20px;
  box-sizing: border-box;
}

.upload-panel__row {
  flex: 1;
  min-height: 0;
  height: 100%;
}

.upload-panel__row :deep(.el-col) {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.failure-section {
  max-width: 560px;
  margin: 48px auto;
  padding: 24px;
}

.hero-text {
  margin-bottom: 24px;
  animation: fadeIn 0.8s ease 0.2s both;
}

.hero-text h1 {
  font-size: 22px;
  color: var(--ops-text, #0f172a);
  margin-bottom: 8px;
  font-weight: 600;
  text-shadow: none;
  line-height: 1.3;
}

.hero-text p {
  font-size: 14px;
  color: #64748b;
  margin-bottom: 0;
  font-weight: 400;
  max-width: 720px;
  margin-left: auto;
  margin-right: auto;
  line-height: 1.6;
}

.upload-box {
  flex: 1;
  width: 100%;
  min-height: 0;
  background: var(--ops-panel-soft, #f8fafc);
  border: 2px dashed var(--ops-border, #cbd5e1);
  border-radius: var(--ops-radius-sm, 10px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease;
  box-sizing: border-box;
  padding: 24px;
  text-align: center;
}

.upload-guide {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0 4px 4px;
  box-sizing: border-box;
}

.upload-guide__block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.upload-guide__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ops-text, #0f172a);
}

.upload-guide__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.upload-guide__steps {
  margin: 0;
  padding-left: 18px;
  color: var(--ops-text-muted, #64748b);
  font-size: 13px;
  line-height: 1.65;
}

.upload-guide__steps li + li {
  margin-top: 6px;
}

.upload-guide__tip {
  margin-top: auto;
  padding: 10px 12px;
  border-radius: var(--ops-radius-sm, 8px);
  background: rgba(13, 148, 136, 0.06);
  border: 1px solid rgba(13, 148, 136, 0.15);
  font-size: 12px;
  line-height: 1.55;
  color: var(--ops-text-muted, #64748b);
}

.upload-box:hover {
  border-color: var(--ops-primary, #0d9488);
  background: var(--ops-panel-soft, #f8fafc);
}

.upload-icon {
  font-size: 48px;
  color: var(--ops-primary, #0d9488);
  margin-bottom: 12px;
  opacity: 0.85;
}

.upload-hint {
  font-size: 16px;
  color: #1e293b;
  font-weight: 500;
  margin-bottom: 12px;
  transition: color 0.3s ease;
}

.upload-box:hover .upload-hint {
  color: var(--ops-text, #1e293b);
}

.upload-sub {
  font-size: 12px;
  color: #64748b;
  margin-top: 0;
  transition: color 0.3s ease;
}

.upload-box:hover .upload-sub {
  color: #64748b;
}

.hidden-input { display: none; }

/* 处理进度区域 */
.processing-section {
  text-align: center;
  margin: 36px 0;
  padding: 28px;
  background: var(--ops-panel, #ffffff);
  border-radius: var(--ops-radius, 12px);
  border: 1px solid var(--ops-border, #dbe2ea);
  box-shadow: var(--ops-shadow-sm);
}

.processing-spinner {
  width: 40px;
  height: 40px;
  margin: 0 auto 24px;
  border: 3px solid var(--ops-border, #e2e8f0);
  border-top-color: var(--ops-primary, #0d9488);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.scan-text {
  color: var(--ops-text, #0f172a);
  margin-bottom: 8px;
  font-size: 18px;
  font-weight: 600;
}

.current-step {
  color: var(--ops-text-subtle, #64748b);
  font-size: 14px;
  margin-bottom: 16px;
}

.scan-progress {
  width: min(720px, 100%);
  margin: 16px auto;
  height: 16px;
  border-radius: 8px;
  overflow: hidden;
}

.scan-progress .el-progress__bar {
  border-radius: 8px;
  transition: width 0.8s cubic-bezier(0.4, 0, 0.2, 1);
}

.scan-progress .el-progress__bar__inner {
  border-radius: 8px;
  background: var(--ops-primary, #0d9488);
}

.scan-metrics {
  display: flex;
  justify-content: center;
  gap: 30px;
  margin: 20px 0;
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
}

.task-controls {
  display: flex;
  justify-content: center;
  gap: 20px;
  margin-top: 40px;
  flex-wrap: wrap;
}

.task-controls .el-button {
  min-width: 120px;
}

/* KPI仪表盘 */
.kpi-board {
  display: flex;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
  animation: fadeInUp 0.8s ease;
}

.kpi-card {
  background: #ffffff;
  border-radius: 10px;
  flex: 1;
  min-width: 180px;
  padding: 16px;
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  border: 1px solid #dbe2ea;
}

.kpi-card:hover {
  box-shadow: var(--ops-shadow-md, 0 4px 12px rgba(15, 23, 42, 0.08));
}

.health-score {
  flex: 1.8;
  justify-content: flex-start;
  gap: 20px;
  background: #f8fbff;
}

.stat-icon-wrapper {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  transition: all 0.3s ease;
}

.kpi-card:hover .stat-icon-wrapper {
  transform: scale(1.1);
}

.stat-content {
  text-align: left;
  flex: 1;
}

.gauge-chart {
  width: 120px;
  height: 120px;
  flex-shrink: 0;
}

.kpi-info {
  text-align: left;
  flex: 1;
}

.kpi-title {
  font-size: 14px;
  color: #607d8b;
  font-weight: 500;
  margin-bottom: 8px;
}

.kpi-desc {
  font-size: 24px;
  font-weight: 700;
  color: var(--ops-text);
  margin-top: 0;
  line-height: 1.2;
}

.kpi-value {
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 8px;
  line-height: 1;
  text-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.kpi-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 500;
}

.action-card {
  flex-direction: row;
  gap: 16px;
  background: transparent;
  box-shadow: none;
  padding: 0;
  justify-content: flex-end;
  flex-wrap: wrap;
  min-width: 400px;
}

.action-card .el-button {
  border-radius: 12px;
  padding: 12px 24px;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.action-card .el-button:hover {
  transform: translateY(-3px);
  box-shadow: 0 8px 25px rgba(0, 0, 0, 0.15);
}

/* 卡片样式 */
.detail-card,
.chart-card,
.advice-card {
  border-radius: 10px;
  overflow: hidden;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  background: #ffffff;
  border: 1px solid #dbe2ea;
}

.detail-card:hover,
.chart-card:hover,
.advice-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.1);
}

.detail-card .el-card__header,
.chart-card .el-card__header,
.advice-card .el-card__header {
  background: var(--ops-panel-soft);
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  padding: 20px 24px;
  border-radius: 16px 16px 0 0;
}

.detail-card .title,
.chart-card .title,
.advice-card .title {
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
  border-left: 3px solid #0ea5e9;
  padding-left: 10px;
  margin: 0;
}

/* 表格样式 */
.detail-card .el-table {
  border-radius: 0 0 16px 16px;
  overflow: hidden;
}

.detail-card .el-table th {
  background: #f8fafc;
  font-weight: 600;
  color: #334155;
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
}

.detail-card .el-table td {
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  transition: background-color 0.3s ease;
}

.detail-card .el-table tr:hover td {
  background: #f0f9ff !important;
}

.event-id-tag {
  color: #4fc3f7;
  cursor: pointer;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  background: rgba(79, 195, 247, 0.1);
  border-radius: 16px;
  transition: all 0.3s ease;
}

.event-id-tag:hover {
  background: rgba(79, 195, 247, 0.2);
  transform: translateX(4px);
  text-decoration: none;
}

/* 图表样式 */
.severity-chart-wrapper {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 20px;
}

.severity-legend {
  flex: 1;
  padding-left: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #64748b;
  font-weight: 500;
  transition: all 0.3s ease;
  cursor: pointer;
  padding: 8px 12px;
  border-radius: 8px;
}

.legend-item:hover {
  background: rgba(79, 195, 247, 0.1);
  transform: translateX(4px);
}

.legend-item .dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  display: inline-block;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
}

/* 建议列表 */
.advice-list {
  margin-top: 20px;
  padding: 0 20px 20px;
}

.advice-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 20px;
  font-size: 14px;
  color: #1e293b;
  line-height: 1.6;
  padding: 16px;
  background: rgba(255, 255, 255, 0.8);
  border-radius: 8px;
  transition: all 0.3s ease;
  border-left: 4px solid transparent;
}

.advice-item:hover {
  transform: translateX(4px);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.08);
}

.advice-item:nth-child(odd) {
  border-left-color: #4fc3f7;
  background: rgba(79, 195, 247, 0.05);
}

.advice-item:nth-child(even) {
  border-left-color: #43a047;
  background: rgba(67, 160, 71, 0.05);
}

/* AI诊断弹窗 */
.ai-dialog {
  border-radius: 20px;
  overflow: hidden;
}

.ai-dialog .el-dialog__header {
  background: var(--ops-primary);
  color: white;
  padding: 24px;
  border-radius: 20px 20px 0 0;
}

.ai-dialog .el-dialog__title {
  color: white;
  font-size: 20px;
  font-weight: 600;
}

.ai-content {
  padding: 32px;
  background: #f8fafc;
}

.ai-header {
  display: flex;
  gap: 20px;
  margin-bottom: 24px;
  align-items: flex-start;
}

.ai-avatar {
  width: 60px;
  height: 60px;
  background: var(--ops-primary-hover);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
  box-shadow: 0 4px 16px rgba(79, 195, 247, 0.3);
  flex-shrink: 0;
}

.ai-bubble {
  background: white;
  padding: 20px;
  border-radius: 16px;
  font-size: 16px;
  color: #334155;
  font-weight: 500;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  flex: 1;
  line-height: 1.6;
}

.analysis-result {
  background: white;
  padding: 24px;
  border-radius: 16px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  min-height: 300px;
}

.ai-markdown-body {
  white-space: pre-wrap;
  font-size: 15px;
  color: #334155;
  line-height: 1.8;
}

.ai-markdown-body h1,
.ai-markdown-body h2,
.ai-markdown-body h3 {
  color: var(--ops-text);
  margin-top: 24px;
  margin-bottom: 16px;
}

.ai-markdown-body code {
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 14px;
}

.ai-markdown-body pre {
  background: #f1f5f9;
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin: 16px 0;
}

.ai-markdown-body pre code {
  background: transparent;
  padding: 0;
}

/* 日志详情弹窗 */
.log-content-box {
  background: #f8fafc;
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #e2e8f0;
  font-family: 'Courier New', Consolas, Monaco, monospace;
  font-size: 14px;
  color: #334155;
  max-height: 250px;
  overflow-y: auto;
  white-space: pre-wrap;
  line-height: 1.6;
  box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.05);
}

.stack-trace-box {
  background: #0f172a;
  color: #e0e0e0;
  padding: 20px;
  border-radius: 12px;
  font-family: 'Courier New', Consolas, Monaco, monospace;
  font-size: 13px;
  max-height: 250px;
  overflow-y: auto;
  white-space: pre;
  line-height: 1.5;
  box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.3);
}

.stack-trace-box::-webkit-scrollbar {
  width: 8px;
}

.stack-trace-box::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
}

.stack-trace-box::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 4px;
}

/* 动画效果 */
@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(40px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .hero-text h1 {
    font-size: 36px;
  }
  
  .upload-box {
    min-height: 200px;
  }
  
  .scan-progress {
    width: 500px;
  }
  
  .kpi-board {
    flex-direction: column;
  }
  
  .kpi-card {
    width: 100%;
    flex: none;
  }
  
  .action-card {
    min-width: 100%;
    justify-content: center;
  }
}

@media (max-width: 768px) {
  .log-analysis-page--fill {
    min-height: calc(100vh - var(--ops-header-h, 52px));
  }

  .upload-panel :deep(.el-card__body) {
    padding: 16px;
  }

  .upload-panel__row :deep(.el-col) {
    min-height: auto;
  }

  .upload-box {
    flex: none;
    min-height: 220px;
  }

  .upload-guide {
    flex: none;
    min-height: auto;
    padding-left: 0;
    margin-top: 12px;
  }

  .upload-guide__tip {
    margin-top: 0;
  }

  .hero-text h1 {
    font-size: 28px;
  }
  
  .hero-text p {
    font-size: 16px;
  }
  
  .processing-section {
    padding: 40px 20px;
    margin: 40px 0;
  }
  
  .scan-progress {
    width: 100%;
  }
  
  .scan-metrics {
    flex-direction: column;
    gap: 16px;
  }
  
  .task-controls {
    flex-direction: column;
    align-items: center;
  }
  
  .task-controls .el-button {
    width: 200px;
  }
  
  .kpi-card {
    padding: 20px;
  }
  
  .ai-content {
    padding: 20px;
  }
  
  .ai-avatar {
    width: 48px;
    height: 48px;
    font-size: 24px;
  }
}

/* 滚动条样式 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: rgba(0, 0, 0, 0.05);
  border-radius: 4px;
}

::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.2);
  border-radius: 4px;
  transition: background 0.3s ease;
}

::-webkit-scrollbar-thumb:hover {
  background: rgba(0, 0, 0, 0.3);
}
</style>
