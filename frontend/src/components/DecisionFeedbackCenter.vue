<template>
  <div class="ops-page decision-feedback-page">
    <OpsPageHeader
      title="决策反馈"
      subtitle="标注未训练样本 · 提交人工结论 · 触发模型再训练（管理员）"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="loadData">刷新</el-button>
        <el-button
          size="small"
          type="primary"
          plain
          :disabled="!selectedIds.length"
          :loading="saving"
          @click="onBatchSubmit"
        >
          批量提交标注
        </el-button>
        <el-button
          v-if="isAdmin"
          size="small"
          type="warning"
          :loading="training"
          @click="onTriggerTrain"
        >
          触发再训练
        </el-button>
      </template>
    </OpsPageHeader>

    <el-alert
      v-if="!isAdmin"
      type="info"
      :closable="false"
      show-icon
      title="当前为普通用户：可提交标注，触发再训练仅管理员可用。"
      style="margin-bottom: 12px;"
    />

    <div class="stat-row">
      <div class="stat-item">
        <span class="stat-label">未训练样本</span>
        <strong class="stat-value">{{ untrainedCount }}</strong>
      </div>
      <div class="stat-item">
        <span class="stat-label">本页已选</span>
        <strong class="stat-value">{{ selectedIds.length }}</strong>
      </div>
    </div>

    <el-card shadow="never" class="ops-surface-card" v-loading="loading">
      <el-table
        :data="rows"
        border
        stripe
        height="520"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="48" />
        <el-table-column prop="decisionId" label="决策 ID" min-width="160" show-overflow-tooltip />
        <el-table-column prop="logLevel" label="级别" width="88" />
        <el-table-column prop="logContent" label="日志摘要" min-width="220" show-overflow-tooltip />
        <el-table-column label="窗口错误率" width="110">
          <template #default="{ row }">
            {{ formatRate(row.errorRate1m) }}
          </template>
        </el-table-column>
        <el-table-column label="模型置信度" width="110">
          <template #default="{ row }">
            {{ formatRate(row.modelConfidence) }}
          </template>
        </el-table-column>
        <el-table-column label="人工标注" width="160">
          <template #default="{ row }">
            <el-radio-group v-model="row.actualAlert" size="small">
              <el-radio-button :value="1">告警</el-radio-button>
              <el-radio-button :value="0">正常</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
        <el-table-column prop="reviewer" label="标注人" width="100" show-overflow-tooltip />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" :loading="row._saving" @click="onSubmitOne(row)">
              提交
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager-wrap">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          layout="total, prev, pager, next, sizes"
          :total="total"
          :page-sizes="[10, 20, 50]"
          @current-change="loadData"
          @size-change="onPageSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import OpsPageHeader from './OpsPageHeader.vue'
import {
  getUntrainedSamples,
  getUntrainedCount,
  submitDecisionFeedback,
  submitDecisionFeedbackBatch,
  triggerManualTraining
} from '../api'

const isAdmin = computed(() => {
  try {
    const user = JSON.parse(localStorage.getItem('user') || '{}')
    return Number(user.role ?? 0) === 1
  } catch {
    return false
  }
})

const loading = ref(false)
const saving = ref(false)
const training = ref(false)
const rows = ref([])
const total = ref(0)
const untrainedCount = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const selectedRows = ref([])

const selectedIds = ref([])

function formatRate(v) {
  if (v == null || Number.isNaN(Number(v))) return '—'
  const n = Number(v)
  if (n <= 1) return `${(n * 100).toFixed(1)}%`
  return String(n)
}

function onSelectionChange(selection) {
  selectedRows.value = selection
  selectedIds.value = selection.map((r) => r.id).filter((id) => id != null)
}

async function loadData() {
  loading.value = true
  try {
    const [pageRes, countRes] = await Promise.all([
      getUntrainedSamples(pageNum.value, pageSize.value),
      getUntrainedCount()
    ])
    const list = pageRes?.list ?? pageRes?.records ?? []
    rows.value = list.map((item) => ({
      ...item,
      actualAlert: item.actualAlert == null ? 1 : Number(item.actualAlert),
      _saving: false
    }))
    total.value = Number(pageRes?.total ?? 0)
    untrainedCount.value = Number(countRes ?? pageRes?.total ?? 0)
  } catch (e) {
    ElMessage.error(e.message || '加载未训练样本失败')
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onPageSizeChange() {
  pageNum.value = 1
  loadData()
}

async function onSubmitOne(row) {
  if (row.actualAlert !== 0 && row.actualAlert !== 1) {
    ElMessage.warning('请先选择告警或正常')
    return
  }
  row._saving = true
  try {
    await submitDecisionFeedback({
      decisionId: row.decisionId,
      actualAlert: row.actualAlert,
      reviewer: row.reviewer || '',
      remark: row.remark || '',
      logContent: row.logContent,
      logLevel: row.logLevel,
      logTemplate: row.logTemplate,
      modelConfidence: row.modelConfidence,
      errorRate1m: row.errorRate1m,
      error1m: row.error1m,
      total1m: row.total1m,
      intervalMs: row.intervalMs
    })
    ElMessage.success('已提交标注')
    await loadData()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally {
    row._saving = false
  }
}

async function onBatchSubmit() {
  if (!selectedRows.value.length) return
  saving.value = true
  try {
    const payload = selectedRows.value.map((row) => ({
      decisionId: row.decisionId,
      actualAlert: Number(row.actualAlert),
      reviewer: row.reviewer || '',
      remark: row.remark || '',
      logContent: row.logContent,
      logLevel: row.logLevel,
      logTemplate: row.logTemplate,
      modelConfidence: row.modelConfidence,
      errorRate1m: row.errorRate1m,
      error1m: row.error1m,
      total1m: row.total1m,
      intervalMs: row.intervalMs
    }))
    const n = await submitDecisionFeedbackBatch(payload)
    ElMessage.success(`批量提交成功 ${n ?? payload.length} 条`)
    await loadData()
  } catch (e) {
    ElMessage.error(e.message || '批量提交失败')
  } finally {
    saving.value = false
  }
}

async function onTriggerTrain() {
  if (!isAdmin.value) {
    ElMessage.warning('仅管理员可触发再训练')
    return
  }
  try {
    await ElMessageBox.confirm('将基于当前反馈样本触发模型再训练，是否继续？', '触发再训练', {
      type: 'warning',
      confirmButtonText: '开始训练',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  training.value = true
  try {
    const ok = await triggerManualTraining()
    if (ok) {
      ElMessage.success('再训练已触发并完成')
    } else {
      ElMessage.warning('训练已调用，但返回未成功（可能样本不足）')
    }
    await loadData()
  } catch (e) {
    ElMessage.error(e.message || '触发训练失败')
  } finally {
    training.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.stat-row {
  display: flex;
  gap: 16px;
  margin-bottom: 14px;
}

.stat-item {
  min-width: 140px;
  padding: 12px 16px;
  background: var(--el-fill-color-blank, #fff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
}

.stat-label {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  margin-bottom: 4px;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary, #303133);
}

.pager-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.ops-surface-card {
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
}
</style>
