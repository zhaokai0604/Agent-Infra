<!-- 遗留页，未挂入主导航；能力已并入统一审计/态势。如需启用请在 App.vue 注册。 -->
<template>
  <div :class="embedded ? 'audit-pane' : 'ops-page'">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="遗留页，未挂入主导航；能力已并入统一审计/态势。如需启用请在 App.vue 注册。"
      style="margin-bottom: 12px;"
    />
    <OpsPageHeader
      v-if="!embedded"
      title="访问记录"
      subtitle="API 与页面访问审计，含用户、IP、耗时与状态码"
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
          <el-button size="small" type="success" plain :disabled="!rows.length" :loading="exporting" @click="onExportCsv">
            导出 CSV
          </el-button>
        </div>
      </template>

      <div class="audit-export-wrap">
        <el-table :data="rows" border stripe height="560" v-loading="loading">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="user_id" label="用户ID" width="100" />
          <el-table-column prop="user_role" label="角色" width="80" />
          <el-table-column prop="remote_ip" label="IP" width="140" />
          <el-table-column prop="method" label="方法" width="80" />
          <el-table-column prop="path" label="路径" min-width="240" show-overflow-tooltip />
          <el-table-column prop="status" label="状态码" width="90" />
          <el-table-column prop="duration_ms" label="耗时(ms)" width="110" />
          <el-table-column prop="request_bytes" label="请求字节" width="100" />
          <el-table-column prop="created_at" label="时间" min-width="180" />
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAiAuditRecent } from '../api'
import { formatLocalDateKey } from '../utils/formatDate.js'
import { exportRowsAsCsv } from '../utils/tableExport.js'
import OpsPageHeader from './OpsPageHeader.vue'

defineProps({
  embedded: { type: Boolean, default: false }
})

const AUDIT_CSV_COLUMNS = [
  { key: 'id', label: 'ID' },
  { key: 'user_id', label: '用户ID' },
  { key: 'user_role', label: '角色' },
  { key: 'remote_ip', label: 'IP' },
  { key: 'method', label: '方法' },
  { key: 'path', label: '路径' },
  { key: 'status', label: '状态码' },
  { key: 'duration_ms', label: '耗时(ms)' },
  { key: 'request_bytes', label: '请求字节' },
  { key: 'created_at', label: '时间' }
]

const rows = ref([])
const loading = ref(false)
const limit = ref(100)
const exporting = ref(false)

const loadData = async () => {
  loading.value = true
  try {
    rows.value = await getAiAuditRecent(limit.value)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const onExportCsv = () => {
  if (!rows.value.length) {
    ElMessage.warning('暂无数据可导出')
    return
  }
  exporting.value = true
  try {
    exportRowsAsCsv({
      rows: rows.value,
      columns: AUDIT_CSV_COLUMNS,
      filename: `ai-audit-${formatLocalDateKey(new Date())}.csv`
    })
    ElMessage.success('已导出 CSV')
  } catch (e) {
    ElMessage.error(e?.message || 'CSV 导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
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
</style>
