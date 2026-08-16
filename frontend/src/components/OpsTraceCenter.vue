<template>
  <div :class="embedded ? 'audit-pane' : 'ops-page'">
    <OpsPageHeader
      v-if="!embedded"
      title="AWM 记忆 · 执行链路"
      subtitle="Agent 处置套路 / 安全教训，以及工具调用全链路：预览、确认、执行结果与审计步骤"
    >
      <template #actions>
        <el-input-number v-model="limit" :min="10" :max="500" :step="10" size="small" />
        <el-button size="small" type="primary" :loading="loading" @click="loadData">刷新</el-button>
        <el-button size="small" type="success" plain :disabled="!rows.length" :loading="exporting" @click="onExportCsv">
          导出 CSV
        </el-button>
      </template>
    </OpsPageHeader>

  <el-card shadow="never" class="ops-surface-card">
    <template v-if="embedded" #header>
        <div class="header-actions header-actions--solo">
          <el-input-number v-model="limit" :min="10" :max="500" :step="10" size="small" />
          <el-button size="small" type="primary" :loading="loading" @click="loadData">刷新</el-button>
          <el-button size="small" type="success" plain :disabled="!rows.length" :loading="exporting" @click="onExportCsv">导出 CSV</el-button>
        </div>
    </template>

    <OpsMemoryPanel ref="memoryPanelRef" />

    <div class="audit-export-wrap">
    <el-table :data="rows" border stripe height="520">
      <el-table-column prop="traceId" label="追踪 ID" min-width="200" show-overflow-tooltip />
      <el-table-column prop="channel" label="通道" width="76" />
      <el-table-column prop="riskLevel" label="风险" width="88" />
      <el-table-column prop="securityOutcome" label="安全结论" width="130" show-overflow-tooltip />
      <el-table-column prop="toolName" label="工具" width="140" show-overflow-tooltip>
        <template #default="scope">
          {{ mcpToolDisplayName(scope.row.toolName) }}
        </template>
      </el-table-column>
      <el-table-column prop="targetHostLabel" label="目标主机" width="140" show-overflow-tooltip />
      <el-table-column prop="executionOk" label="成功" width="72">
        <template #default="scope">
          <el-tag :type="scope.row.executionOk ? 'success' : 'danger'" size="small">
            {{ scope.row.executionOk ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时ms" width="92" />
      <el-table-column prop="resultSummary" label="摘要" min-width="200" show-overflow-tooltip>
        <template #default="scope">
          {{ formatSummaryCell(scope.row.resultSummary) }}
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="时间" min-width="170" show-overflow-tooltip />
      <el-table-column label="详情" width="100" fixed="right">
        <template #default="scope">
          <el-button type="primary" link size="small" @click="openCot(scope.row.traceId)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <el-dialog
      v-model="cotVisible"
      width="720px"
      destroy-on-close
      class="cot-dialog"
      @opened="onCotOpened"
    >
      <template #header>
        <div class="cot-dialog-header">
          <span class="cot-dialog-title">执行详情</span>
          <div v-if="cotDetail.traceId && !cotLoading" class="cot-dialog-actions">
            <el-button size="small" type="success" plain :loading="cotExporting" @click="onExportCotCsv">
              导出 CSV
            </el-button>
          </div>
        </div>
      </template>
      <div class="cot-export-wrap">
      <div v-if="cotLoading" class="cot-loading">加载中…</div>
      <template v-else-if="cotDetail.traceId">
        <el-descriptions :column="1" border size="small" class="cot-meta">
          <el-descriptions-item label="追踪 ID">{{ cotDetail.traceId }}</el-descriptions-item>
          <el-descriptions-item label="通道">{{ cotDetail.channel }}</el-descriptions-item>
          <el-descriptions-item label="工具">{{ mcpToolDisplayName(cotDetail.toolName) }}</el-descriptions-item>
          <el-descriptions-item label="安全结论">{{ cotDetail.securityOutcome }}</el-descriptions-item>
          <el-descriptions-item label="风险">{{ cotDetail.riskLevel }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">执行步骤</el-divider>
        <div class="cot-steps">
          <el-card
            v-for="(step, idx) in cotStepsNormalized"
            :key="idx"
            shadow="never"
            class="cot-step-card"
            :class="{ 'cot-step-card--workflow': step.isWorkflow }"
            body-style="padding: 12px 14px"
          >
            <template #header>
              <div class="cot-step-header">
                <el-tag size="small" :type="step.isWorkflow ? 'warning' : 'primary'" effect="plain">
                  {{ step.isWorkflow ? 'AWM' : `步骤 ${idx + 1}` }}
                </el-tag>
                <span class="cot-step-title">{{ step.title }}</span>
              </div>
            </template>
            <StructuredResultView :view-model="step.view" />
          </el-card>
        </div>
        <el-divider v-if="cotDetail.resultSummary" content-position="left">结果摘要</el-divider>
        <el-card
          v-if="cotDetail.resultSummary"
          shadow="never"
          class="cot-summary-card"
          body-style="padding: 12px 14px"
        >
          <StructuredResultView :data="cotDetail.resultSummary" />
        </el-card>
      </template>
      <div v-else class="cot-empty">暂无数据</div>
      </div>
    </el-dialog>
  </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getOpsTraceRecent, getOpsTraceDetail } from '../api'
import { buildStructuredViewModel, coerceStructuredInput, formatResultPreview } from '../utils/structuredDataView'
import { traceStepTitle } from '../utils/traceStepLabels'
import StructuredResultView from './StructuredResultView.vue'
import OpsMemoryPanel from './OpsMemoryPanel.vue'
import { formatLocalDateKey } from '../utils/formatDate.js'
import { exportRowsAsCsv } from '../utils/tableExport.js'
import { mcpToolDisplayName } from '../utils/mcpToolsMeta'
import OpsPageHeader from './OpsPageHeader.vue'

const TRACE_LIST_CSV_COLUMNS = [
  { key: 'traceId', label: 'traceId' },
  { key: 'channel', label: '通道' },
  { key: 'riskLevel', label: '风险' },
  { key: 'securityOutcome', label: '安全结论' },
  { key: 'toolName', label: '工具' },
  { key: 'executionOk', label: '成功' },
  { key: 'durationMs', label: '耗时ms' },
  { key: 'resultSummary', label: '摘要' },
  { key: 'createdAt', label: '时间' }
]

const COT_CSV_COLUMNS = [
  { key: 'traceId', label: 'traceId' },
  { key: 'channel', label: '通道' },
  { key: 'toolName', label: '工具' },
  { key: 'securityOutcome', label: '安全结论' },
  { key: 'riskLevel', label: '风险' },
  { key: 'stepIndex', label: '步骤序号' },
  { key: 'stepTitle', label: '步骤标题' },
  { key: 'stepDetail', label: '步骤详情' }
]

const rows = ref([])
const loading = ref(false)
const limit = ref(100)
const exporting = ref(false)

const cotVisible = ref(false)
const cotLoading = ref(false)
const cotDetail = ref({})
const pendingTraceId = ref('')
const cotExporting = ref(false)
const memoryPanelRef = ref(null)

const isWorkflowPhase = (step) => {
  const phase = String(step?.phase || step?.title || '').toLowerCase()
  const detail = String(step?.detail || '')
  return phase === 'workflow' || detail.includes('AWM workflow')
}

function normalizeStepEvidence(rawBody) {
  if (rawBody == null) return null
  if (typeof rawBody !== 'string') return rawBody

  let text = rawBody.replace(/\s+/g, ' ').trim()
  text = text.replace(/^\[Step\s*\d+\s*-\s*[^\]]+\]\s*/i, '')

  const objectStart = text.indexOf('{')
  const objectEnd = text.lastIndexOf('}')
  if (objectStart >= 0 && objectEnd > objectStart) {
    const before = text.slice(0, objectStart).replace(/[：:\s]+$/, '').trim()
    const payload = coerceStructuredInput(text.slice(objectStart, objectEnd + 1))
    return before ? { summary: before, result: payload } : payload
  }

  return coerceStructuredInput(text)
}

const loadData = async () => {
  loading.value = true
  try {
    rows.value = await getOpsTraceRecent(limit.value)
    memoryPanelRef.value?.refreshAll?.()
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

const cotStepsNormalized = computed(() => {
  const steps = cotDetail.value?.steps
  if (!Array.isArray(steps)) return []
  return steps.map((s, idx) => {
    const title = traceStepTitle(s)
    const rawBody = s?.detail != null ? s.detail : s
    const parsed = normalizeStepEvidence(rawBody)
    const isWorkflow = isWorkflowPhase(s)
    return {
      title: `${idx + 1}. ${title}`,
      view: buildStructuredViewModel(parsed),
      rawBody,
      isWorkflow
    }
  })
})

function formatSummaryCell(raw) {
  if (raw == null || raw === '') return '—'
  const text = formatResultPreview(raw, 200)
  return text && text !== '—' ? text : String(raw).slice(0, 120)
}

const openCot = (traceId) => {
  if (!traceId) return
  pendingTraceId.value = String(traceId)
  cotDetail.value = {}
  if (cotVisible.value) {
    // 对话框已开着时 @opened 不会再触发，需直接拉详情
    onCotOpened()
    return
  }
  cotVisible.value = true
}

const onCotOpened = async () => {
  const id = pendingTraceId.value
  if (!id) return
  cotLoading.value = true
  try {
    cotDetail.value = await getOpsTraceDetail(id)
  } catch (e) {
    ElMessage.error(e.message || '加载详情失败')
    cotDetail.value = {}
  } finally {
    cotLoading.value = false
  }
}

const buildCotCsvRows = () => {
  const d = cotDetail.value || {}
  const base = {
    traceId: d.traceId ?? '',
    channel: d.channel ?? '',
    toolName: d.toolName ?? '',
    securityOutcome: d.securityOutcome ?? '',
    riskLevel: d.riskLevel ?? ''
  }
  const stepRows = cotStepsNormalized.value.map((step, idx) => ({
    ...base,
    stepIndex: idx + 1,
    stepTitle: step.title,
    stepDetail: step.rawBody
  }))
  if (d.resultSummary != null && d.resultSummary !== '') {
    stepRows.push({
      ...base,
      stepIndex: '摘要',
      stepTitle: '结果摘要',
      stepDetail: d.resultSummary
    })
  }
  return stepRows
}

const cotExportBasename = () => {
  const tid = String(cotDetail.value?.traceId || 'unknown').replace(/[^\w-]/g, '').slice(0, 24)
  return `ops-cot-${tid}-${formatLocalDateKey(new Date())}`
}

const onExportCotCsv = () => {
  if (!cotDetail.value?.traceId) {
    ElMessage.warning('请先等待思维链加载完成')
    return
  }
  const csvRows = buildCotCsvRows()
  if (!csvRows.length) {
    ElMessage.warning('暂无思维链数据可导出')
    return
  }
  cotExporting.value = true
  try {
    exportRowsAsCsv({
      rows: csvRows,
      columns: COT_CSV_COLUMNS,
      filename: `${cotExportBasename()}.csv`
    })
    ElMessage.success('思维链已导出 CSV')
  } catch (e) {
    ElMessage.error(e?.message || 'CSV 导出失败')
  } finally {
    cotExporting.value = false
  }
}

const onExportCsv = () => {
  if (!rows.value.length) {
    ElMessage.warning('暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    const exportRows = rows.value.map((r) => ({
      ...r,
      executionOk: r.executionOk ? '是' : '否'
    }))
    exportRowsAsCsv({
      rows: exportRows,
      columns: TRACE_LIST_CSV_COLUMNS,
      filename: `ops-trace-audit-${formatLocalDateKey(new Date())}.csv`
    })
    ElMessage.success('已导出 CSV')
  } catch (e) {
    ElMessage.error(e?.message || 'CSV 导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(() => loadData())

defineExpose({ openTrace: openCot })
</script>

<style scoped>
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.header-actions--solo {
  margin-left: auto;
  width: 100%;
  justify-content: flex-end;
}
.audit-pane .ops-surface-card {
  border: none;
  box-shadow: none;
}
.audit-export-wrap {
  background: var(--ops-panel);
}
.cot-dialog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-right: 24px;
}
.cot-dialog-title {
  font-weight: 600;
}
.cot-dialog-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.cot-loading,
.cot-empty {
  padding: 24px;
  text-align: center;
  color: #909399;
}
.cot-meta {
  margin-bottom: 12px;
}
.cot-steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.cot-step-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cot-step-title {
  font-weight: 500;
}
.cot-step-card--workflow {
  border-color: #f3d19e;
  background: #fdf6ec;
}
.cot-summary-card {
  margin-top: 4px;
}
</style>
