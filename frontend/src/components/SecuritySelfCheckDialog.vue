<template>
  <el-dialog
    v-model="visible"
    title="一键安全自检"
    width="720px"
    class="sec-self-check-dialog"
    destroy-on-close
    @open="onOpen"
  >
    <div v-loading="loading" class="sec-check-body">
      <template v-if="report">
        <div class="sec-check-head">
          <el-tag :type="statusTagType(report.overallStatus)" size="large" effect="dark">
            {{ statusLabel(report.overallStatus) }}
          </el-tag>
          <span class="sec-check-summary">{{ summaryHeadline }}</span>
          <span v-if="report.checkedAt" class="sec-check-time">检测时间 {{ formatTime(report.checkedAt) }}</span>
        </div>
        <p v-if="report.subtitle" class="sec-intro">{{ report.subtitle }}</p>

        <div v-if="judgeCategories.length" class="sec-judge-cats">
          <div v-for="c in judgeCategories" :key="c.id" class="sec-judge-cat">
            <el-tag size="small" type="info" effect="plain">{{ c.title }}</el-tag>
            <span class="sec-judge-desc">{{ c.description }}</span>
          </div>
        </div>

        <div v-if="configRows.length" class="sec-config-grid">
          <div v-for="row in configRows" :key="row.key" class="sec-config-card">
            <div class="sec-config-label">{{ row.label }}</div>
            <div class="sec-config-val">{{ row.value }}</div>
          </div>
        </div>

        <el-divider content-position="left">检查项</el-divider>

        <div v-for="layer in report.layers || []" :key="layer.id" class="sec-layer-block">
          <div class="sec-layer-title">
            <el-icon v-if="layer.ok" class="ok-icon"><CircleCheck /></el-icon>
            <el-icon v-else class="warn-icon"><Warning /></el-icon>
            <span>{{ layer.name }}</span>
            <el-tag size="small" :type="layer.ok ? 'success' : 'warning'">
              {{ layer.passed }}/{{ layer.total }}
            </el-tag>
          </div>
          <p class="sec-layer-desc">{{ layer.description }}</p>
          <el-table
            :data="probesForLayer(layer.id)"
            size="small"
            stripe
            class="sec-probe-table"
            :show-header="true"
          >
            <el-table-column prop="title" label="检查项" min-width="120" />
            <el-table-column prop="scenario" label="测试场景" min-width="160" show-overflow-tooltip />
            <el-table-column label="预期" width="88" align="center">
              <template #default="{ row }">
                <el-tag size="small" type="info">{{ decisionLabel(row.expect) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="实际" width="88" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="decisionTagType(row.actual)">{{ decisionLabel(row.actual) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="结果" width="64" align="center">
              <template #default="{ row }">
                <el-icon v-if="row.passed" class="ok-icon"><CircleCheck /></el-icon>
                <el-icon v-else class="fail-icon"><CircleClose /></el-icon>
              </template>
            </el-table-column>
          </el-table>
          <ul class="sec-hints">
            <li v-for="p in probesForLayer(layer.id)" :key="p.id + '-hint'">
              <strong>{{ p.title }}：</strong>{{ p.description || p.judgeHint }}
            </li>
          </ul>
        </div>
      </template>
      <el-empty v-else-if="!loading" description="暂无检查结果" />
    </div>
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
      <el-button type="primary" :loading="loading" @click="load">重新检测</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { CircleCheck, CircleClose, Warning } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getSecuritySelfCheck } from '../api/index.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const report = ref(null)

const summaryHeadline = computed(() => report.value?.summary?.headline || '')
const judgeCategories = computed(() => {
  const c = report.value?.judgeCategories
  return Array.isArray(c) ? c : []
})

const configRows = computed(() => {
  const c = report.value?.config
  if (!c) return []
  return [
    { key: 'auto', label: '自动执行上限（风险分）', value: `< ${c.riskScoreAutoMax}` },
    { key: 'confirm', label: '须确认区间', value: `[${c.riskScoreAutoMax}, ${c.riskScoreConfirmMax}]` },
    { key: 'tools', label: 'HTTP 白名单工具数', value: String(c.httpAllowedToolCount ?? '—') },
    { key: 'deny', label: '只读面禁写工具数', value: String(c.readOnlyDenyToolCount ?? '—') },
    { key: 'dry', label: '全局演练模式', value: c.globalDryRun ? '已开启' : '关闭' },
    { key: 'min', label: '最小权限执行', value: c.minPrivilegeEnabled ? `是（${c.runAsUser || '—'}）` : '否' }
  ]
})

function probesForLayer (layerId) {
  const list = report.value?.probes
  if (!Array.isArray(list)) return []
  return list.filter((p) => p.layer === layerId)
}

function statusLabel (s) {
  if (s === 'PASS') return '检查通过'
  if (s === 'WARN') return '部分异常'
  return '待检查'
}

function statusTagType (s) {
  if (s === 'PASS') return 'success'
  if (s === 'WARN') return 'warning'
  return 'info'
}

function decisionLabel (d) {
  const u = String(d || '').toUpperCase()
  if (u === 'ALLOW') return '放行'
  if (u === 'NEED_CONFIRM') return '须确认'
  if (u === 'BLOCK') return '拒绝'
  return d || '—'
}

function decisionTagType (d) {
  const u = String(d || '').toUpperCase()
  if (u === 'ALLOW') return 'success'
  if (u === 'NEED_CONFIRM') return 'warning'
  if (u === 'BLOCK') return 'danger'
  return 'info'
}

function formatTime (iso) {
  try {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    return d.toLocaleString('zh-CN', { hour12: false })
  } catch {
    return iso
  }
}

async function load () {
  loading.value = true
  try {
    report.value = await getSecuritySelfCheck()
  } catch (e) {
    ElMessage.error(e?.message || '安全自检请求失败')
    report.value = null
  } finally {
    loading.value = false
  }
}

function onOpen () {
  if (!report.value) load()
}

watch(visible, (v) => {
  if (v && !report.value) load()
})
</script>

<style scoped>
.sec-check-body {
  min-height: 200px;
}
.sec-check-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.sec-check-summary {
  flex: 1;
  font-size: 13px;
  color: #334155;
  min-width: 200px;
}
.sec-check-time {
  font-size: 11px;
  color: #94a3b8;
  width: 100%;
}
.sec-intro {
  font-size: 12px;
  color: #475569;
  margin: 0 0 12px;
  line-height: 1.5;
}
.sec-judge-cats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 14px;
}
@media (max-width: 560px) {
  .sec-judge-cats {
    grid-template-columns: 1fr;
  }
}
.sec-judge-cat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  background: #f1f5f9;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}
.sec-judge-desc {
  font-size: 11px;
  color: #64748b;
  line-height: 1.4;
}
.sec-config-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 8px;
}
@media (max-width: 640px) {
  .sec-config-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
.sec-config-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 12px;
}
.sec-config-label {
  font-size: 11px;
  color: #64748b;
  margin-bottom: 4px;
}
.sec-config-val {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}
.sec-layer-block {
  margin-bottom: 20px;
}
.sec-layer-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 4px;
}
.sec-layer-desc {
  font-size: 12px;
  color: #64748b;
  margin: 0 0 8px;
  line-height: 1.45;
}
.sec-probe-table {
  margin-bottom: 8px;
}
.sec-hints {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 11px;
  color: #475569;
  line-height: 1.5;
}
.sec-hints li {
  padding: 2px 0;
}
.ok-icon {
  color: #22c55e;
}
.warn-icon {
  color: #f59e0b;
}
.fail-icon {
  color: #ef4444;
}
</style>
