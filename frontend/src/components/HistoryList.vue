<template>
  <div class="ops-page history-container">
    <OpsPageHeader title="诊断报告" subtitle="历史日志分析任务、状态与结果摘要">
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="fetchHistory">刷新</el-button>
      </template>
    </OpsPageHeader>

    <el-card shadow="never" class="ops-surface-card">
      <!-- 筛选条件 -->
      <div class="filter-container">
        <el-form :inline="true" :model="filterForm" class="filter-form">
          <el-form-item label="任务名称">
            <el-input
              v-model="filterForm.fileName"
              placeholder="输入文件名"
              clearable
              @keyup.enter="applyFilter"
            />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="filterForm.status" placeholder="选择状态" clearable>
              <el-option label="等待中" value="PENDING" />
              <el-option label="处理中" value="PROCESSING" />
              <el-option label="已完成" value="COMPLETED" />
              <el-option label="已失败" value="FAILED" />
              <el-option label="已暂停" value="PAUSED" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间范围">
            <el-date-picker
              v-model="filterForm.timeRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
              format="YYYY-MM-DD"
              clearable
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="applyFilter">筛选</el-button>
            <el-button @click="resetFilter">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
      
      <el-table :data="tasks" stripe style="width: 100%" v-loading="loading">
        <el-table-column prop="taskId" label="任务 ID" width="220" show-overflow-tooltip />
        <el-table-column prop="fileName" label="文件名" width="200" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
             <template #default="scope">
                <el-tag :type="getStatusType(scope.row.status)">{{ scope.row.status }}</el-tag>
             </template>
        </el-table-column>
        <el-table-column label="分析结果摘要">
             <template #default="scope">
                <div v-if="scope.row.summary">
                    <span>总计: {{ scope.row.summary.totalLogs }} 条 | </span>
                    <span class="text-danger">异常: {{ scope.row.summary.anomalyCount }} 条</span>
                </div>
                <div v-else>-</div>
             </template>
        </el-table-column>
        <el-table-column prop="createTime" label="任务时间" width="180">
            <template #default="scope">
                {{ formatTime(scope.row.createTime) }}
            </template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="scope">
            <el-button type="primary" link @click="viewTaskDetail(scope.row.taskId)">查看详情</el-button>
            <el-button type="info" link @click="$emit('view-task', scope.row.taskId)">查看报告</el-button>
            <el-button
              v-if="scope.row.status === 'FAILED'"
              type="warning"
              link
              @click="goOpsAgentForHistoryRow(scope.row)"
            >去对话排查</el-button>
            <el-button
              v-if="shouldOfferAgentDeepDive(scope.row)"
              type="success"
              link
              @click="goOpsAgentDeepDive(scope.row)"
            >继续分析</el-button>
            <el-button type="danger" link @click="confirmDelete(scope.row.taskId)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      
      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          layout="total, prev, pager, next"
          :total="total"
          @current-change="handlePageChange"
        />
      </div>
    </el-card>
    
    <!-- 删除确认对话框 -->
    <el-dialog
      v-model="dialogVisible"
      title="确认删除"
      width="400px"
    >
      <span>确定删除这份报告吗？此操作不可恢复。</span>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">取消</el-button>
          <el-button type="danger" @click="handleDelete" :loading="deleteLoading">
            确定删除
          </el-button>
        </span>
      </template>
    </el-dialog>
    
    <!-- 任务详情对话框 -->
    <el-dialog
      v-model="detailDialogVisible"
      title="任务详情"
      width="800px"
      center
    >
      <div v-if="loadingDetail" class="loading-wrapper">
        <el-skeleton :rows="10" animated />
      </div>
      <div v-else-if="currentTask" class="task-detail">
        <!-- 基本信息 -->
        <el-card shadow="never" class="detail-card">
          <template #header><span class="detail-title">基本信息</span></template>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="任务ID">{{ currentTask.taskId }}</el-descriptions-item>
            <el-descriptions-item label="文件名">{{ currentTask.fileName }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ getStatusText(currentTask.status) }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatTime(currentTask.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="当前进度" :span="2">{{ currentTask.progress }}%</el-descriptions-item>
            <el-descriptions-item label="当前步骤" :span="2">{{ currentTask.currentStep }}</el-descriptions-item>
            <el-descriptions-item label="错误信息" :span="2" v-if="currentTask.errorMsg">
              <span style="color: #F56C6C">{{ currentTask.errorMsg }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
        
        <!-- 统计信息 -->
        <el-card shadow="never" class="detail-card" v-if="currentTask.summary">
          <template #header><span class="detail-title">统计信息</span></template>
          <el-descriptions :column="4" border>
            <el-descriptions-item label="日志总行数">{{ currentTask.summary.totalLogs }}</el-descriptions-item>
            <el-descriptions-item label="异常日志数">{{ currentTask.summary.anomalyCount }}</el-descriptions-item>
            <el-descriptions-item label="异常率">{{ (currentTask.summary.anomalyRate * 100).toFixed(2) }}%</el-descriptions-item>
            <el-descriptions-item label="处理耗时">{{ (currentTask.summary.costTime / 1000).toFixed(2) }}秒</el-descriptions-item>
          </el-descriptions>
        </el-card>
        
        <!-- AI诊断结果 -->
        <el-card shadow="never" class="detail-card" v-if="currentTask.aiDiagnosis">
          <template #header><span class="detail-title">AI诊断结果</span></template>
          <div class="ai-diagnosis">
            {{ currentTask.aiDiagnosis }}
          </div>
        </el-card>
        
        <!-- 分析结果 -->
        <el-card shadow="never" class="detail-card" v-if="currentTask.result && currentTask.result.length > 0">
          <template #header><span class="detail-title">分析结果</span></template>
          <el-table :data="currentTask.result.slice(0, 10)" stripe style="width: 100%">
            <el-table-column prop="logTime" label="日志时间" width="180" />
            <el-table-column prop="severity" label="级别" width="100">
              <template #default="scope">
                <el-tag :type="getStatusType(scope.row.severity)">{{ scope.row.severity }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="desensitizedLog" label="日志内容" show-overflow-tooltip />
          </el-table>
          <div v-if="currentTask.result.length > 10" class="more-records">
            显示前10条记录，共{{ currentTask.result.length }}条
          </div>
        </el-card>
      </div>
      <div v-else class="empty-detail">
        未找到任务详情
      </div>
      <template #footer>
        <span class="dialog-footer">
          <el-button
            v-if="currentTask && shouldOfferAgentDeepDive(currentTask)"
            type="success"
            @click="goOpsAgentFromDetailDeep"
          >继续分析</el-button>
          <el-button
            v-if="currentTask && (currentTask.status === 'FAILED' || currentTask.errorMsg)"
            type="primary"
            @click="goOpsAgentFromDetail"
          >打开对话</el-button>
          <el-button @click="detailDialogVisible = false">关闭</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getHistory, deleteTask, getReport } from '../api'
import { ElMessage } from 'element-plus'
import OpsPageHeader from './OpsPageHeader.vue'
import {
  dispatchOpsAgentPrefill,
  shouldOfferAgentDeepDive,
  agentPrefillHighAnomaly,
  agentPrefillFailedTask
} from '../utils/opsAgentNavigate'

const tasks = ref([])
const total = ref(0)
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const dialogVisible = ref(false)
const deleteLoading = ref(false)
const currentTaskId = ref('')

// 任务详情相关
const detailDialogVisible = ref(false)
const loadingDetail = ref(false)
const currentTask = ref(null)

// 筛选表单数据
const filterForm = ref({
  fileName: '',
  status: '',
  timeRange: []
})

/** 将日期选择器值规范为 YYYY-MM-DD（兼容 Date / ISO 字符串） */
const toDateOnly = (v) => {
  if (v == null || v === '') return ''
  if (v instanceof Date) {
    const y = v.getFullYear()
    const m = String(v.getMonth() + 1).padStart(2, '0')
    const d = String(v.getDate()).padStart(2, '0')
    return `${y}-${m}-${d}`
  }
  const s = String(v).trim()
  const m = s.match(/^(\d{4}-\d{2}-\d{2})/)
  return m ? m[1] : ''
}

/** 组装查询参数：空值不传，避免后端收到空字符串仍当条件 */
const buildHistoryQuery = () => {
  const q = {}
  const name = (filterForm.value.fileName || '').trim()
  const status = (filterForm.value.status || '').trim()
  if (name) q.fileName = name
  if (status) q.status = status
  const range = filterForm.value.timeRange
  if (Array.isArray(range) && range.length === 2) {
    let start = toDateOnly(range[0])
    let end = toDateOnly(range[1])
    if (start && end && start > end) {
      const tmp = start
      start = end
      end = tmp
    }
    if (start) q.startTime = start
    if (end) q.endTime = end
  }
  return q
}

const fetchHistory = async () => {
  loading.value = true
  try {
    const res = await getHistory(currentPage.value, pageSize.value, buildHistoryQuery())
    const list = res?.list ?? res?.records ?? []
    tasks.value = Array.isArray(list) ? list : []
    total.value = Number(res?.total ?? 0)
  } catch (e) {
    console.error('加载历史任务失败', e)
    ElMessage.error('加载失败: ' + (e?.message || '请检查登录与网络'))
    tasks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 筛选：回到第 1 页再请求 */
const applyFilter = async () => {
  currentPage.value = 1
  await fetchHistory()
  const n = total.value
  if (n === 0) {
    ElMessage.info('未找到符合条件的任务，请调整筛选条件')
  } else {
    ElMessage.success(`已筛选，共 ${n} 条记录`)
  }
}

const handlePageChange = (page) => {
    currentPage.value = page
    fetchHistory() // 翻页时重新从后端获取数据
}

// 重置筛选条件并刷新列表
const resetFilter = async () => {
  filterForm.value = {
    fileName: '',
    status: '',
    timeRange: []
  }
  currentPage.value = 1
  await fetchHistory()
  ElMessage.success('已重置筛选条件')
}

// 查看任务详情
const viewTaskDetail = async (taskId) => {
  detailDialogVisible.value = true
  loadingDetail.value = true
  currentTask.value = null
  
  try {
    const taskData = await getReport(taskId)
    currentTask.value = taskData
  } catch (error) {
    ElMessage.error('获取任务详情失败: ' + error.message)
  } finally {
    loadingDetail.value = false
  }
}

const goOpsAgentForHistoryRow = (row) => {
  dispatchOpsAgentPrefill(agentPrefillFailedTask(row))
}

const goOpsAgentDeepDive = (row) => {
  dispatchOpsAgentPrefill(agentPrefillHighAnomaly(row))
}

const goOpsAgentFromDetail = () => {
  if (!currentTask.value) return
  const t = currentTask.value
  const err = t.errorMsg ? `报错：${t.errorMsg}。` : ''
  const msg = `任务「${t.taskId}」状态 ${t.status}。${err}请协助排查日志异常与磁盘占用情况。`
  detailDialogVisible.value = false
  dispatchOpsAgentPrefill(msg)
}

const goOpsAgentFromDetailDeep = () => {
  if (!currentTask.value) return
  detailDialogVisible.value = false
  dispatchOpsAgentPrefill(agentPrefillHighAnomaly(currentTask.value))
}

const getStatusType = (status) => {
    if (status === 'COMPLETED') return 'success'
    if (status === 'FAILED') return 'danger'
    if (status === 'PROCESSING') return 'primary'
    return 'info'
}

const getStatusText = (status) => {
    const statusMap = {
        'PENDING': '等待中',
        'PROCESSING': '处理中',
        'COMPLETED': '已完成',
        'FAILED': '已失败',
        'PAUSED': '已暂停'
    }
    return statusMap[status] || status
}

const formatTime = (val) => {
    if (!val) return '-'
    // Handle LocalDateTime array [year, month, day, hour, minute, second]
    if (Array.isArray(val)) {
        const [y, m, d, h, min, s] = val
        const pad = (n) => String(n).padStart(2,'0')
        return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(min)}:${pad(s||0)}`
    }
    // Handle ISO string
    if (typeof val === 'string' && val.includes('T')) {
        return val.replace('T', ' ').substring(0, 19)
    }
    return val
}

// 确认删除
const confirmDelete = (taskId) => {
    currentTaskId.value = taskId
    dialogVisible.value = true
}

// 处理删除
const handleDelete = async () => {
    if (!currentTaskId.value) return
    
    deleteLoading.value = true
    try {
        await deleteTask(currentTaskId.value)
        ElMessage.success('删除成功')
        dialogVisible.value = false
        // 重新获取任务列表
        fetchHistory()
    } catch (error) {
        ElMessage.error('删除失败: ' + error.message)
    } finally {
        deleteLoading.value = false
    }
}

onMounted(() => {
    fetchHistory()
})
</script>

<style scoped>
.history-container {
  min-height: 100%;
  background: #f3f4f6;
  padding: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-container {
  margin: 0 0 12px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}

.filter-form {
  display: flex;
  gap: 10px;
  align-items: end;
  flex-wrap: wrap;
}

.filter-form .el-form-item {
  margin-bottom: 0;
}

.filter-form .el-input,
.filter-form .el-select,
.filter-form .el-date-picker {
  min-width: 190px;
}

.title {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}

.detail-title {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  border-left: 3px solid #0ea5e9;
  padding-left: 8px;
}

.detail-card {
  margin-bottom: 12px;
}

.task-detail {
  max-height: 600px;
  overflow-y: auto;
}

.loading-wrapper {
  padding: 16px;
}

.empty-detail {
  text-align: center;
  padding: 24px 0;
  color: #64748b;
}

.ai-diagnosis {
  white-space: pre-wrap;
  line-height: 1.6;
  color: #1e293b;
  font-size: 13px;
}

.more-records {
  text-align: right;
  margin-top: 10px;
  color: #64748b;
  font-size: 12px;
}

.pagination-wrapper {
  margin-top: 12px;
  display: flex;
  justify-content: center;
  padding: 10px;
  background: #f8fafc;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}

.el-table__header-wrapper {
  background: #f8fafc;
}

.el-table__header-wrapper th {
  font-weight: 600;
  color: #334155;
  background: transparent;
}

.el-table__row:hover {
  background: #f1f5f9 !important;
}

@media (max-width: 768px) {
  .history-container {
    padding: 8px;
  }

  .title {
    font-size: 15px;
  }
}
</style>