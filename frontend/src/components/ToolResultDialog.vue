<template>
  <el-dialog
    v-model="visible"
    width="780px"
    destroy-on-close
    class="tool-result-dialog"
    :show-close="true"
    @closed="onClosed"
  >
    <template #header>
      <div class="dialog-header">
        <div v-if="payload" class="dialog-header__badge">{{ toolBadge }}</div>
        <div>
          <div class="dialog-header__title">{{ displayName }}</div>
          <div class="dialog-header__meta">
            <span class="status-chip" :class="statusChipClass">
              {{ statusLabel }}
            </span>
            <span v-if="payload.duration" class="meta-duration">{{ payload.duration }} ms</span>
          </div>
        </div>
      </div>
    </template>

    <div v-if="payload" class="result-body">
      <div class="result-structured">
        <StructuredResultView :data="viewPayload" />
      </div>

      <div v-if="showTrace && payload.traceId" class="trace-bar">
        <span class="trace-label">追踪 ID</span>
        <code class="trace-id">{{ payload.traceId }}</code>
        <span class="trace-hint">可在「溯源 → 执行链路」查看完整记录</span>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <el-button @click="visible = false">关闭</el-button>
        <el-button v-if="showTrace && payload?.traceId" type="primary" @click="goTrace">前往溯源</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StructuredResultView from './StructuredResultView.vue'
import { mcpToolDisplayName, mcpToolBadge } from '../utils/mcpToolsMeta'
import { payloadForStructuredView } from '../utils/structuredDataView'

const visible = ref(false)
const payload = ref(null)
const showTrace = ref(true)

const displayName = computed(() =>
  payload.value?.toolName ? mcpToolDisplayName(payload.value.toolName) : '执行结果'
)

const toolBadge = computed(() =>
  payload.value?.toolName ? mcpToolBadge(payload.value.toolName) : 'MCP'
)

const viewPayload = computed(() =>
  payload.value?.raw != null ? payloadForStructuredView(payload.value.raw) : null
)

const statusLabel = computed(() => {
  const p = payload.value
  if (!p) return '执行失败'
  if (p.writeMismatch) return '写意图未落地'
  const status = String(p.status || '').toUpperCase()
  if (status === 'WARN' && p.success) return '完成（告警）'
  const mode = String(p.mode || '').toUpperCase()
  if ((mode === 'DRY-RUN' || mode === 'PREVIEW') && p.success) return '预览成功'
  if (mode === 'SCAN' && p.success) return '扫描完成'
  if (mode === 'NOOP' && p.success) return '无文件可删'
  if ((mode === 'DELETE' || mode === 'EXECUTED') && p.success) return '写操作已落地'
  if (p.success) return '调用成功'
  return '执行失败'
})

const statusChipClass = computed(() => {
  const p = payload.value
  if (!p) return 'fail'
  if (p.writeMismatch) return 'warn'
  if (String(p.status || '').toUpperCase() === 'WARN' && p.success) return 'warn'
  const mode = String(p.mode || '').toUpperCase()
  if ((mode === 'DRY-RUN' || mode === 'PREVIEW' || mode === 'SCAN' || mode === 'NOOP') && p.success) return 'preview'
  if (p.success) return 'ok'
  return 'fail'
})

function pickMode(raw) {
  if (!raw || typeof raw !== 'object') return ''
  if (typeof raw.mode === 'string') return raw.mode
  const data = raw.data
  if (data && typeof data === 'object' && typeof data.mode === 'string') return data.mode
  return ''
}

function open(result) {
  if (!result) return
  showTrace.value = result.showTrace !== false
  const raw = result.raw ?? result.fullResult ?? result
  const nestedStatus =
    (raw && typeof raw === 'object' && raw.status) ||
    (raw?.data && typeof raw.data === 'object' && raw.data.status) ||
    ''
  payload.value = {
    toolName: result.toolName,
    success: result.success === true,
    duration: result.duration || 0,
    traceId: result.traceId || result.raw?.traceId || result.raw?.trace_id || '',
    raw,
    mode: result.mode || pickMode(raw),
    status: result.status || nestedStatus || (result.success === true ? 'SUCCESS' : 'ERROR'),
    writeMismatch:
      result.writeMismatch === true ||
      raw?.writeMismatch === true
  }
  visible.value = true
}

function onClosed() {
  payload.value = null
}

function goTrace() {
  const tid = payload.value?.traceId
  visible.value = false
  window.dispatchEvent(
    new CustomEvent('ops-navigate-tab', { detail: { tab: 'audit', traceId: tid } })
  )
  if (tid) {
    ElMessage.info('已打开「溯源」，可在执行链路中查看详情')
  }
}

defineExpose({ open })
</script>

<style scoped>
.dialog-header {
  display: flex;
  align-items: center;
  gap: 14px;
}

.dialog-header__badge {
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  border-radius: 11px;
  background: rgba(13, 148, 136, 0.12);
  color: var(--ops-primary, #0d9488);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--ops-font-mono, monospace);
}

.dialog-header__title {
  font-size: 17px;
  font-weight: 600;
  color: var(--ops-text, #0f172a);
  line-height: 1.3;
}

.dialog-header__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}

.status-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}

.status-chip.ok {
  background: rgba(34, 197, 94, 0.12);
  color: #15803d;
}

.status-chip.preview {
  background: rgba(59, 130, 246, 0.12);
  color: #1d4ed8;
}

.status-chip.warn {
  background: rgba(245, 158, 11, 0.14);
  color: #b45309;
}

.status-chip.fail {
  background: rgba(239, 68, 68, 0.1);
  color: #b91c1c;
}

.meta-duration {
  font-size: 12px;
  color: var(--ops-text-subtle, #64748b);
  font-variant-numeric: tabular-nums;
}

.result-body {
  padding-top: 4px;
}

.result-structured {
  max-height: min(56vh, 480px);
  overflow-y: auto;
  padding: 12px 14px;
  border: 1px solid var(--ops-border, #e2e8f0);
  border-radius: 10px;
  background: var(--ops-panel-soft, #f8fafc);
}

.trace-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--ops-panel-soft, #f1f5f9);
  font-size: 12px;
}

.trace-label {
  color: var(--ops-text-subtle, #64748b);
  font-weight: 600;
}

.trace-id {
  font-family: var(--ops-font-mono, monospace);
  background: var(--ops-panel, #fff);
  padding: 2px 8px;
  border-radius: 4px;
  border: 1px solid var(--ops-border, #e2e8f0);
}

.trace-hint {
  color: var(--ops-text-subtle, #64748b);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>

<style>
.tool-result-dialog .el-dialog__header {
  padding-bottom: 8px;
  margin-right: 0;
}

.tool-result-dialog .el-dialog__body {
  padding-top: 8px;
}
</style>
