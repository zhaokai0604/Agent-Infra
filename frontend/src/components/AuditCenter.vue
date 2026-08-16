<template>
  <div class="ops-page unified-audit-center">
    <OpsPageHeader
      title="统一审计中心"
      subtitle="集中查看工具调用、对话、访问与安全拦截记录，默认展示可读摘要和结构化证据。"
    >
      <template #actions>
        <el-input-number v-model="limit" :min="20" :max="300" :step="20" size="small" />
        <el-button size="small" :loading="loading" @click="refreshActive">刷新</el-button>
        <el-button size="small" type="success" plain :disabled="!activeRows.length" @click="exportCsv">
          导出 CSV
        </el-button>
      </template>
    </OpsPageHeader>

    <el-tabs v-model="activePane" class="audit-tabs" @tab-change="onPaneChange">
      <el-tab-pane label="工具" name="tools" />
      <el-tab-pane label="对话" name="dialogue" />
      <el-tab-pane label="访问" name="access" />
      <el-tab-pane label="安全" name="security" />
    </el-tabs>

    <div class="audit-summary-row">
      <article v-for="card in summaryCards" :key="card.key" class="audit-summary-card">
        <span class="audit-summary-card__label">{{ card.label }}</span>
        <strong>{{ card.count }}</strong>
      </article>
    </div>

    <div v-if="activePane === 'security'" class="security-panel" v-loading="selfCheckLoading">
      <div class="security-panel__head">
        <div>
          <h3>安全门禁自检</h3>
          <p>检查高危操作拦截、二次确认、策略判定和证据记录是否按预期工作。</p>
        </div>
        <div class="security-panel__actions">
          <el-tag
            v-if="selfCheck?.overallStatus"
            :type="selfCheck.overallStatus === 'PASS' ? 'success' : 'warning'"
            size="small"
          >
            {{ selfCheck.overallStatus === 'PASS' ? '自检通过' : '存在风险' }}
          </el-tag>
          <el-button size="small" type="primary" :loading="selfCheckLoading" @click="loadSelfCheck">
            重新自检
          </el-button>
          <el-button size="small" @click="openSecurityCockpit">打开安全驾驶舱</el-button>
        </div>
      </div>
      <div v-if="selfCheck?.summary" class="security-headline">
        {{ selfCheck.summary.headline || `共 ${selfCheck.summary.total || 0} 项检查，通过 ${selfCheck.summary.passed || 0} 项` }}
      </div>
      <div v-if="selfCheckLayers.length" class="security-layers">
        <article v-for="layer in selfCheckLayers" :key="layer.id" class="security-layer" :class="{ 'is-ok': layer.ok }">
          <strong>{{ layer.name }}</strong>
          <span>{{ layer.ok ? '通过' : '需关注' }} · {{ layer.passed }}/{{ layer.total }}</span>
          <p>{{ layer.description }}</p>
        </article>
      </div>
      <el-table v-if="selfCheckProbes.length" :data="selfCheckProbes" border stripe size="small" max-height="220">
        <el-table-column prop="title" label="检查项" min-width="160" show-overflow-tooltip />
        <el-table-column label="期望" width="110">
          <template #default="{ row }">{{ humanGateExpect(row.expect) }}</template>
        </el-table-column>
        <el-table-column label="实际" width="110">
          <template #default="{ row }">{{ humanGateExpect(row.actual) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.passed ? 'success' : 'danger'">
              {{ row.passed ? '通过' : '异常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="judgeHint" label="判定说明" min-width="220" show-overflow-tooltip />
      </el-table>
    </div>

    <div v-if="activePane !== 'access'" class="audit-filters">
      <el-radio-group v-model="kindFilter" size="small" @change="loadFeed">
        <el-radio-button
          v-for="option in paneKindOptions"
          :key="option.value"
          :label="option.value"
        >
          {{ option.label }}
        </el-radio-button>
      </el-radio-group>
    </div>

    <div class="audit-layout">
      <el-card shadow="never" class="audit-list-card">
        <el-table
          :data="activeRows"
          border
          stripe
          height="560"
          v-loading="loading"
          row-key="entryId"
          highlight-current-row
          @row-click="openRow"
        >
          <el-table-column v-if="activePane !== 'access'" label="类型" width="108">
            <template #default="{ row }">
              <el-tag size="small" :type="kindTagType(row.auditKind)">
                {{ kindText(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对象 / 摘要" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ rowBrief(row) }}</template>
          </el-table-column>
          <el-table-column v-if="activePane === 'access'" prop="remoteIp" label="来源 IP" width="130" show-overflow-tooltip />
          <el-table-column label="结果" width="128">
            <template #default="{ row }">
              <el-tag size="small" :type="outcomeTagType(row)">{{ humanOutcome(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="operatorUserId" label="操作人" width="96" show-overflow-tooltip />
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ formatDurationMs(row.durationMs) || '-' }}</template>
          </el-table-column>
          <el-table-column label="时间" min-width="158">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="72" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link @click.stop="openRow(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="audit-detail-card" v-loading="detailLoading">
        <template #header>
          <div class="audit-detail-card__header">
            <div>
              <h3>审计详情</h3>
              <p>{{ selected ? kindText(selected) : '选择左侧记录后查看摘要、步骤和证据。' }}</p>
            </div>
            <div class="audit-detail-card__header-actions">
              <el-tag v-if="selected" size="small" :type="outcomeTagType(selected)">
                {{ humanOutcome(selected) }}
              </el-tag>
              <el-button
                v-if="selected?.traceId"
                size="small"
                type="success"
                plain
                @click="jumpOpsTrace(selected.traceId)"
              >
                查看执行链路
              </el-button>
            </div>
          </div>
        </template>

        <el-empty v-if="!selected" description="暂无选中的审计记录" />

        <template v-else>
          <div class="audit-narrative">
            {{ narrateAudit(selected) }}
          </div>

          <div class="audit-detail-meta">
            <div class="meta-item">
              <span class="meta-item__label">入口</span>
              <strong>{{ humanChannel(selected.requestChannel || selected.channel) }}</strong>
            </div>
            <div class="meta-item">
              <span class="meta-item__label">时间</span>
              <strong>{{ formatTime(selected.createdAt) }}</strong>
            </div>
            <div v-if="selected.remoteIp" class="meta-item">
              <span class="meta-item__label">来源 IP</span>
              <strong>{{ selected.remoteIp }}</strong>
            </div>
            <div v-if="toolLabel(selected)" class="meta-item">
              <span class="meta-item__label">工具</span>
              <strong>{{ toolLabel(selected) }}</strong>
            </div>
            <div v-if="formatDurationMs(selected.durationMs)" class="meta-item">
              <span class="meta-item__label">耗时</span>
              <strong>{{ formatDurationMs(selected.durationMs) }}</strong>
            </div>
            <div v-if="selected.operatorUserId" class="meta-item">
              <span class="meta-item__label">操作人</span>
              <strong>{{ selected.operatorUserId }}</strong>
            </div>
          </div>

          <div v-if="selected.userInput" class="audit-detail-block">
            <div class="audit-detail-block__title">用户输入</div>
            <div class="audit-detail-block__body">{{ selected.userInput }}</div>
          </div>

          <div v-if="selectedBusinessSummary" class="audit-detail-block">
            <div class="audit-detail-block__title">业务结果</div>
            <div class="audit-detail-block__body">
              {{ selectedBusinessSummary }}
            </div>
          </div>

          <div class="audit-detail-block">
            <div class="audit-detail-block__title">执行步骤</div>
            <div v-if="selectedSteps.length" class="timeline-list">
              <div v-for="(step, index) in selectedSteps" :key="index" class="timeline-step">
                <span class="timeline-step__index">{{ index + 1 }}</span>
                <div class="timeline-step__content">
                  <strong>{{ humanStepTitle(step, index) }}</strong>
                  <div class="timeline-step__preview">{{ stepPreview(step, index) }}</div>
                </div>
              </div>
            </div>
            <el-empty v-else description="暂无执行步骤" />
          </div>

          <div class="audit-detail-block audit-detail-block--evidence">
            <div class="audit-detail-block__title">结构化证据</div>
            <div class="audit-detail-block__body audit-evidence">
              <div class="audit-evidence__summary">{{ selectedEvidencePreview }}</div>
              <StructuredResultView :data="selectedEvidencePayload" />
            </div>
          </div>
        </template>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getAuditDetail,
  getAuditFeed,
  getAiAuditRecent,
  getOpsTraceDetail,
  getSecuritySelfCheck
} from '../api'
import { formatLocalDateKey } from '../utils/formatDate.js'
import { exportRowsAsCsv } from '../utils/tableExport.js'
import OpsPageHeader from './OpsPageHeader.vue'
import StructuredResultView from './StructuredResultView.vue'
import {
  auditKindLabel,
  formatDurationMs,
  humanChannel,
  humanOutcome,
  humanStepBody,
  humanStepTitle,
  humanToolName,
  narrateAudit,
  outcomeTagType
} from '../utils/auditHumanize.js'
import { formatResultPreview, looksLikeJsonString } from '../utils/structuredDataView'

const activePane = ref('tools')
const kindFilter = ref('all')
const limit = ref(120)
const loading = ref(false)
const detailLoading = ref(false)
const selfCheckLoading = ref(false)
const feedRows = ref([])
const accessRows = ref([])
const selected = ref(null)
const selfCheck = ref(null)
const pendingTraceId = ref('')

const PANE_KINDS = {
  tools: [
    { value: 'all', label: '全部' },
    { value: 'tool', label: '工具调用' },
    { value: 'remediation', label: '处置修复' },
    { value: 'confirm', label: '待确认' }
  ],
  dialogue: [
    { value: 'dialogue', label: '对话' },
    { value: 'all', label: '全部链路' }
  ],
  access: [],
  security: [
    { value: 'block', label: '已拦截' },
    { value: 'confirm', label: '待确认' },
    { value: 'all', label: '全部安全事件' }
  ]
}

const paneKindOptions = computed(() => PANE_KINDS[activePane.value] || [])

const activeRows = computed(() => {
  if (activePane.value === 'access') return accessRows.value
  const rows = feedRows.value
  if (activePane.value === 'tools') {
    if (kindFilter.value === 'all') return rows.filter((r) => ['tool', 'remediation', 'confirm'].includes(r.auditKind))
    return rows.filter((r) => r.auditKind === kindFilter.value)
  }
  if (activePane.value === 'dialogue') {
    if (kindFilter.value === 'all') {
      return rows.filter((r) => ['dialogue', 'tool', 'remediation', 'confirm'].includes(r.auditKind))
    }
    return rows.filter((r) => r.auditKind === 'dialogue')
  }
  if (activePane.value === 'security') {
    if (kindFilter.value === 'all') {
      return rows.filter((r) => ['block', 'confirm'].includes(r.auditKind) || String(r.decision || '').includes('REJECT'))
    }
    return rows.filter((r) => r.auditKind === kindFilter.value)
  }
  return rows
})

const summaryCards = computed(() => {
  if (activePane.value === 'access') {
    const err = accessRows.value.filter((r) => Number(r.httpStatus) >= 400).length
    return [
      { key: 'total', label: '访问记录', count: accessRows.value.length },
      { key: 'error', label: '异常访问', count: err },
      { key: 'ok', label: '正常访问', count: accessRows.value.length - err }
    ]
  }
  const counters = activeRows.value.reduce((acc, row) => {
    const key = row.auditKind || 'unknown'
    acc[key] = (acc[key] || 0) + 1
    return acc
  }, {})
  if (activePane.value === 'security') {
    const probes = selfCheck.value?.summary || {}
    return [
      { key: 'block', label: '拦截事件', count: counters.block || 0 },
      { key: 'confirm', label: '待确认', count: counters.confirm || 0 },
      { key: 'probe', label: '门禁自检', count: `${probes.passed ?? '-'} / ${probes.total ?? '-'}` }
    ]
  }
  return [
    { key: 'total', label: '当前记录', count: activeRows.value.length },
    { key: 'tool', label: '工具调用', count: counters.tool || 0 },
    { key: 'dialogue', label: '对话', count: counters.dialogue || 0 },
    { key: 'remediation', label: '处置', count: counters.remediation || 0 },
    { key: 'confirm', label: '确认', count: counters.confirm || 0 },
    { key: 'block', label: '拦截', count: counters.block || 0 }
  ]
})

const selectedSteps = computed(() => {
  const steps = selected.value?.steps
  return Array.isArray(steps) ? steps : []
})

const selectedEvidencePayload = computed(() => selected.value?.raw || selected.value || null)

const selectedEvidencePreview = computed(() => {
  const payload = selectedEvidencePayload.value
  if (!payload) return '暂无结构化证据'
  return formatResultPreview(payload, 220)
})

const selectedBusinessSummary = computed(() => {
  const raw = selected.value?.effectSummary || selected.value?.resultSummary || ''
  if (!raw) return ''
  return readablePreview(raw, 520)
})

const selfCheckLayers = computed(() => (Array.isArray(selfCheck.value?.layers) ? selfCheck.value.layers : []))
const selfCheckProbes = computed(() => (Array.isArray(selfCheck.value?.probes) ? selfCheck.value.probes : []))

function stepPreview(step, index) {
  const raw = humanStepBody(step)
  const text = readablePreview(raw, 160)
  if (!text) return `第 ${index + 1} 步暂无补充说明`
  return text
}

function readablePreview(raw, maxLen = 160) {
  let text = String(raw ?? '').replace(/\s+/g, ' ').trim()
  if (!text) return ''
  text = text.replace(/^\[Step\s*\d+\s*-\s*[^\]]+\]\s*/i, '')

  if (looksLikeJsonString(text)) {
    return formatResultPreview(text, maxLen)
  }

  const objectStart = text.indexOf('{')
  const objectEnd = text.lastIndexOf('}')
  if (objectStart >= 0 && objectEnd > objectStart) {
    const before = text.slice(0, objectStart).replace(/[：:\s]+$/, '').trim()
    const preview = formatResultPreview(text.slice(objectStart, objectEnd + 1), Math.max(80, maxLen - before.length - 4))
    text = `${before ? `${before}：` : ''}${preview}`
  }

  return text.length > maxLen ? `${text.slice(0, maxLen)}…` : text
}

function kindText(row) {
  return auditKindLabel(row?.auditKind) || row?.auditKindLabel || '记录'
}

function toolLabel(row) {
  return humanToolName(row?.toolName)
}

function rowBrief(row) {
  const tool = humanToolName(row?.toolName)
  if (tool) return tool
  if (row?.targetName) return String(row.targetName).slice(0, 80)
  if (row?.userInput) return String(row.userInput).slice(0, 80)
  return '运维记录'
}

function humanGateExpect(code) {
  const c = String(code || '').toUpperCase()
  if (c === 'BLOCK') return '拦截'
  if (c === 'ALLOW') return '允许'
  if (c.includes('CONFIRM')) return '需确认'
  return code || '-'
}

function defaultKindForPane(pane) {
  if (pane === 'dialogue') return 'dialogue'
  if (pane === 'security') return 'block'
  return 'all'
}

function feedKindParam() {
  if (activePane.value === 'tools') return kindFilter.value === 'all' ? '' : kindFilter.value
  if (activePane.value === 'dialogue') return kindFilter.value === 'all' ? '' : 'dialogue'
  if (activePane.value === 'security') return kindFilter.value === 'all' ? '' : kindFilter.value
  return ''
}

async function loadFeed() {
  loading.value = true
  try {
    const kind = feedKindParam()
    const data = await getAuditFeed(limit.value, kind || 'all')
    feedRows.value = Array.isArray(data) ? data : []
    if (pendingTraceId.value) {
      const tid = pendingTraceId.value
      pendingTraceId.value = ''
      await openTrace(tid)
    } else if (selected.value?.entryId) {
      const latest = feedRows.value.find((item) => item.entryId === selected.value.entryId)
      if (latest) selected.value = { ...selected.value, ...latest }
    }
  } catch (error) {
    ElMessage.error(error.message || '审计记录加载失败')
  } finally {
    loading.value = false
  }
}

async function loadAccess() {
  loading.value = true
  try {
    const raw = await getAiAuditRecent(limit.value)
    const list = Array.isArray(raw) ? raw : []
    accessRows.value = list.map((row) => ({
      entryId: `api:${row.id}`,
      auditKind: 'access',
      auditKindLabel: '接口访问',
      targetName: `${row.method || ''} ${row.path || ''}`.trim(),
      remoteIp: row.remote_ip || row.remoteIp || '',
      httpStatus: row.status,
      userRole: row.user_role || row.userRole || '',
      operatorUserId: row.user_id || row.userId || '',
      durationMs: row.duration_ms ?? row.durationMs,
      createdAt: row.created_at || row.createdAt,
      resultSummary: Number(row.status) >= 400 ? `接口返回异常状态 ${row.status}` : `接口访问成功，状态 ${row.status}`,
      raw: row
    }))
  } catch (error) {
    ElMessage.error(error.message || '访问审计加载失败')
    accessRows.value = []
  } finally {
    loading.value = false
  }
}

async function loadSelfCheck() {
  selfCheckLoading.value = true
  try {
    selfCheck.value = await getSecuritySelfCheck()
  } catch (error) {
    ElMessage.error(error.message || '安全自检失败')
  } finally {
    selfCheckLoading.value = false
  }
}

function openSecurityCockpit() {
  window.dispatchEvent(new CustomEvent('ops-navigate-tab', { detail: { tab: 'security-cockpit' } }))
}

async function refreshActive() {
  if (activePane.value === 'access') {
    await loadAccess()
    return
  }
  if (activePane.value === 'security') {
    await Promise.all([loadFeed(), loadSelfCheck()])
    return
  }
  await loadFeed()
}

function onPaneChange() {
  kindFilter.value = defaultKindForPane(activePane.value)
  selected.value = null
  refreshActive()
}

async function openRow(row) {
  const entryId = row?.entryId || ''
  const traceId = row?.traceId || ''
  if (!entryId && !traceId) return
  detailLoading.value = true
  try {
    let detail = null
    if (row.auditKind === 'access' && row.raw && !traceId) {
      detail = {
        ...row,
        steps: [
          { phase: 'request', detail: `访问接口：${row.targetName}` },
          {
            phase: 'identity',
            detail: `操作人：${row.operatorUserId || '未知'}，角色：${row.userRole || '未知'}，来源 IP：${row.remoteIp || '未知'}`
          },
          { phase: 'result', detail: row.resultSummary || humanOutcome(row) }
        ]
      }
    } else {
      detail = await getAuditDetail({ entryId, traceId })
    }
    if (traceId && (!detail?.steps || !detail.steps.length)) {
      try {
        const rich = await getOpsTraceDetail(traceId)
        if (rich && typeof rich === 'object') {
          const steps = Array.isArray(rich.steps)
            ? rich.steps
            : (typeof rich.stepsJsonRaw === 'string' ? safeParseJson(rich.stepsJsonRaw)
              : (typeof rich.stepsJson === 'string' ? safeParseJson(rich.stepsJson) : []))
          detail = {
            ...(detail || {}),
            ...normalizeTraceDetail(rich, detail),
            steps: steps?.length ? steps : (detail?.steps || [])
          }
        }
      } catch {
        // 详情增强失败不影响基础审计记录展示。
      }
    }
    selected.value = detail && Object.keys(detail).length ? detail : row
  } catch (error) {
    ElMessage.error(error.message || '审计详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

function normalizeTraceDetail(rich, fallback = {}) {
  return {
    entryId: fallback.entryId || `trace:${rich.traceId}`,
    traceId: rich.traceId || fallback.traceId,
    auditKind: fallback.auditKind,
    auditKindLabel: fallback.auditKindLabel,
    targetName: rich.targetName || rich.toolName || fallback.targetName,
    toolName: rich.toolName || fallback.toolName,
    decision: rich.decision || rich.securityOutcome || fallback.decision,
    securityOutcome: rich.securityOutcome || fallback.securityOutcome,
    requestChannel: rich.requestChannel || rich.channel || fallback.requestChannel,
    resultSummary: rich.resultSummary || fallback.resultSummary,
    effectSummary: rich.effectSummary || fallback.effectSummary,
    userInput: rich.userInput || fallback.userInput,
    operatorUserId: rich.operatorUserId || fallback.operatorUserId,
    durationMs: rich.durationMs ?? fallback.durationMs,
    createdAt: rich.createdAt || fallback.createdAt,
    executionOk: rich.executionOk ?? fallback.executionOk,
    raw: rich
  }
}

function safeParseJson(text) {
  try {
    const v = JSON.parse(text)
    return Array.isArray(v) ? v : []
  } catch {
    return []
  }
}

function kindTagType(auditKind) {
  switch (auditKind) {
    case 'access':
      return 'info'
    case 'dialogue':
      return ''
    case 'tool':
      return 'success'
    case 'remediation':
      return 'warning'
    case 'block':
      return 'danger'
    case 'confirm':
      return 'warning'
    default:
      return 'info'
  }
}

function formatTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN')
}

function exportCsv() {
  if (!activeRows.value.length) {
    ElMessage.warning('暂无可导出的审计记录')
    return
  }
  const rows = activeRows.value.map((row) => ({
    ...row,
    _story: narrateAudit(row),
    _outcome: humanOutcome(row),
    _tool: humanToolName(row.toolName),
    _channel: humanChannel(row.requestChannel || row.channel)
  }))
  exportRowsAsCsv({
    rows,
    columns: [
      { key: '_story', label: '摘要' },
      { key: '_outcome', label: '结果' },
      { key: 'auditKindLabel', label: '类型' },
      { key: '_tool', label: '工具' },
      { key: 'targetName', label: '目标' },
      { key: '_channel', label: '入口' },
      { key: 'operatorUserId', label: '操作人' },
      { key: 'remoteIp', label: 'IP' },
      { key: 'durationMs', label: '耗时ms' },
      { key: 'createdAt', label: '时间' },
      { key: 'traceId', label: 'traceId' }
    ],
    filename: `审计中心-${activePane.value}-${formatLocalDateKey(new Date())}.csv`
  })
  ElMessage.success('已导出 CSV')
}

async function openTrace(traceId) {
  if (!traceId) return
  if (activePane.value === 'access') activePane.value = 'tools'
  const row = feedRows.value.find((item) => item.traceId === traceId)
  if (row) {
    await openRow(row)
    return
  }
  if (!feedRows.value.length) {
    pendingTraceId.value = traceId
    await loadFeed()
    return
  }
  await openRow({ traceId, entryId: `trace:${traceId}` })
}

function jumpOpsTrace(traceId) {
  if (!traceId) return
  window.dispatchEvent(new CustomEvent('ops-navigate-tab', {
    detail: { tab: 'ops-trace', traceId }
  }))
}

function onNavigateTab(event) {
  const detail = event?.detail || {}
  if (!['audit', 'ops-audit-trace'].includes(detail.tab)) return
  if (detail.traceId) openTrace(detail.traceId)
}

watch(activePane, (pane) => {
  if (!PANE_KINDS[pane]?.some((o) => o.value === kindFilter.value)) {
    kindFilter.value = defaultKindForPane(pane)
  }
})

onMounted(() => {
  refreshActive()
  window.addEventListener('ops-navigate-tab', onNavigateTab)
})

onUnmounted(() => {
  window.removeEventListener('ops-navigate-tab', onNavigateTab)
})

defineExpose({ openTrace, refreshActive })
</script>

<style scoped>
.unified-audit-center {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.audit-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.audit-summary-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}

.audit-summary-card {
  padding: 14px 16px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e2e8f0;
}

.audit-summary-card__label {
  display: block;
  font-size: 12px;
  color: #64748b;
}

.audit-summary-card strong {
  display: block;
  margin-top: 8px;
  font-size: 22px;
  color: #0f172a;
}

.security-panel {
  padding: 16px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.security-panel__head,
.audit-detail-card__header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.security-panel__head h3,
.audit-detail-card__header h3 {
  margin: 0;
  font-size: 16px;
}

.security-panel__head p,
.audit-detail-card__header p {
  margin: 6px 0 0;
  color: #64748b;
  line-height: 1.5;
}

.security-panel__actions,
.audit-detail-card__header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.security-headline {
  padding: 10px 12px;
  border-radius: 8px;
  background: #f0fdf4;
  color: #166534;
  font-size: 13px;
  line-height: 1.5;
}

.security-layers {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 10px;
}

.security-layer {
  padding: 12px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
}

.security-layer.is-ok {
  border-color: rgba(34, 197, 94, 0.35);
  background: #f0fdf4;
}

.security-layer strong {
  display: block;
  color: #0f172a;
}

.security-layer span {
  display: inline-block;
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
}

.security-layer p {
  margin: 8px 0 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}

.audit-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.audit-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(320px, 0.85fr);
  gap: 16px;
  align-items: start;
}

.audit-list-card,
.audit-detail-card {
  border-radius: 8px;
}

.audit-narrative {
  margin-bottom: 14px;
  padding: 14px 16px;
  border-radius: 8px;
  background: #f0fdfa;
  border: 1px solid rgba(13, 148, 136, 0.18);
  color: #134e4a;
  font-size: 14px;
  line-height: 1.7;
}

.audit-detail-meta {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.meta-item {
  padding: 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.meta-item__label {
  display: block;
  margin-bottom: 6px;
  font-size: 12px;
  color: #64748b;
}

.audit-detail-block {
  margin-top: 14px;
}

.audit-detail-block__title {
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.audit-detail-block__body,
.timeline-step__preview {
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  color: #334155;
}

.audit-evidence {
  display: flex;
  flex-direction: column;
  gap: 12px;
  white-space: normal;
}

.audit-evidence__summary {
  padding: 10px 12px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e2e8f0;
  color: #334155;
  line-height: 1.6;
}

.timeline-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.timeline-step {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.timeline-step__index {
  width: 28px;
  height: 28px;
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.12);
  color: #0f766e;
  font-size: 12px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.timeline-step__content {
  flex: 1;
  min-width: 0;
}

.timeline-step__content strong {
  display: block;
  margin-bottom: 6px;
  color: #0f172a;
}

@media (max-width: 1100px) {
  .audit-layout {
    grid-template-columns: 1fr;
  }

  .audit-detail-meta {
    grid-template-columns: 1fr;
  }
}
</style>
