<template>
  <div class="ops-page knowledge-base">
    <OpsPageHeader
      title="知识库"
      subtitle="运维 Runbook / 案例入库，对话时自动检索相似经验"
    >
      <template #actions>
        <el-button :icon="Refresh" @click="refreshAll" :loading="loadingDocs">刷新</el-button>
      </template>
    </OpsPageHeader>

    <el-row :gutter="16" class="status-row">
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="status-card">
          <div class="status-label">向量库</div>
          <div class="status-value" :class="status.qdrantConnected ? 'ok' : 'warn'">
            {{ status.qdrantConnected ? '已连接' : '未连接' }}
          </div>
          <div class="status-meta">{{ status.qdrantUrl || '—' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="status-card">
          <div class="status-label">Embedding</div>
          <div class="status-value" :class="status.embeddingModel === 'available' ? 'ok' : 'warn'">
            {{ embeddingLabel }}
          </div>
          <div class="status-meta">维度 {{ status.vectorDimensions || '—' }}</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="status-card">
          <div class="status-label">已入库</div>
          <div class="status-value ok">{{ status.documentCount ?? 0 }} 篇</div>
          <div class="status-meta">{{ status.pointCount ?? 0 }} 个向量块</div>
        </el-card>
      </el-col>
    </el-row>

    <el-tabs v-model="activeTab" type="border-card" class="kb-tabs ops-tabs">
      <el-tab-pane label="文档管理" name="docs">
        <div class="toolbar">
          <el-button type="primary" :icon="Plus" @click="showUpload = true">新增文档</el-button>
          <el-button :icon="Refresh" @click="refreshAll" :loading="loadingDocs">刷新</el-button>
        </div>

        <el-table :data="documents" v-loading="loadingDocs" stripe class="doc-table">
          <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
          <el-table-column prop="category" label="分类" width="100" />
          <el-table-column prop="source" label="来源" width="120" show-overflow-tooltip />
          <el-table-column prop="chunkCount" label="分块" width="72" align="center" />
          <el-table-column prop="createdAt" label="入库时间" width="180">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="摘要" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.preview }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button type="danger" link @click="removeDoc(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="total"
            layout="total, prev, pager, next"
            @current-change="fetchDocuments"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="检索验证" name="search">
        <div class="search-panel">
          <el-input
            v-model="searchQuery"
            type="textarea"
            :rows="3"
            placeholder="输入运维问题，验证知识库检索效果，例如：磁盘满了怎么清理"
          />
          <el-button type="primary" class="search-btn" :loading="searching" @click="runSearch">
            检索 Top-5
          </el-button>
          <div v-if="searchHits.length" class="hits">
            <el-card v-for="(hit, i) in searchHits" :key="hit.id || i" shadow="never" class="hit-card">
              <div class="hit-head">
                <span class="hit-title">{{ hit.title }}</span>
                <el-tag size="small" type="info">score {{ (hit.score || 0).toFixed(3) }}</el-tag>
              </div>
              <div class="hit-meta">{{ hit.category }} · {{ hit.source }}</div>
              <p class="hit-content">{{ hit.content }}</p>
            </el-card>
          </div>
          <el-empty v-else-if="searchedOnce" description="无命中，请先入库文档" />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showUpload" title="新增知识文档" width="640px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="uploadForm.title" placeholder="例如：磁盘满处置 Runbook" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="uploadForm.category" style="width: 100%">
            <el-option label="通用" value="general" />
            <el-option label="磁盘" value="disk" />
            <el-option label="内存" value="memory" />
            <el-option label="网络" value="network" />
            <el-option label="服务" value="service" />
            <el-option label="安全" value="security" />
          </el-select>
        </el-form-item>
        <el-form-item label="方式">
          <el-radio-group v-model="uploadMode">
            <el-radio value="text">粘贴文本</el-radio>
            <el-radio value="file">上传文件</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="uploadMode === 'text'" label="内容">
          <el-input
            v-model="uploadForm.content"
            type="textarea"
            :rows="12"
            placeholder="支持 Markdown；将自动分块并向量化入库"
          />
        </el-form-item>
        <el-form-item v-else label="文件">
          <el-upload
            drag
            :auto-upload="false"
            :limit="1"
            accept=".txt,.md,.markdown,.pdf,.log,.json,.yml,.yaml"
            :on-change="onFileChange"
            :on-remove="() => { uploadFile = null }"
          >
            <div class="el-upload__text">拖拽或点击上传 txt / md / pdf / log</div>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUpload = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">入库</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import OpsPageHeader from './OpsPageHeader.vue'
import {
  getKnowledgeStatus,
  seedKnowledgeBuiltin,
  listKnowledgeDocuments,
  uploadKnowledgeText,
  uploadKnowledgeFile,
  searchKnowledge,
  deleteKnowledgeDocument
} from '../api'

const activeTab = ref('docs')
const status = ref({})
const documents = ref([])
const loadingDocs = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)

const showUpload = ref(false)
const uploadMode = ref('text')
const uploading = ref(false)
const uploadFile = ref(null)
const uploadForm = ref({ title: '', category: 'general', content: '' })

const searchQuery = ref('')
const searchHits = ref([])
const searching = ref(false)
const searchedOnce = ref(false)

const embeddingLabel = computed(() => {
  if (status.value.embeddingMode === 'spring-ai') return 'DashScope 语义向量'
  if (status.value.embeddingMode === 'local-fallback') return '本地降级向量'
  return '不可用'
})

function formatTime (iso) {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleString('zh-CN')
  } catch {
    return iso
  }
}

async function fetchStatus () {
  try {
    status.value = await getKnowledgeStatus() || {}
  } catch {
    status.value = {}
  }
}

async function fetchDocuments () {
  loadingDocs.value = true
  try {
    const res = await listKnowledgeDocuments(page.value, pageSize.value)
    documents.value = res?.list || []
    total.value = res?.total ?? 0
  } catch {
    documents.value = []
    total.value = 0
  } finally {
    loadingDocs.value = false
  }
}

async function refreshAll () {
  loadingDocs.value = true
  try {
    const seed = await seedKnowledgeBuiltin()
    const n = seed?.seeded ?? 0
    if (n > 0) {
      ElMessage.success(`已写入 ${n} 篇内置 Runbook`)
    }
  } catch (e) {
    ElMessage.warning(e?.message || '种子写入失败，请检查 Qdrant 连接')
  }
  await Promise.all([fetchStatus(), fetchDocuments()])
}

function onFileChange (file) {
  uploadFile.value = file?.raw || null
  if (!uploadForm.value.title && file?.name) {
    uploadForm.value.title = file.name.replace(/\.[^.]+$/, '')
  }
}

async function submitUpload () {
  uploading.value = true
  try {
    if (uploadMode.value === 'file') {
      if (!uploadFile.value) {
        ElMessage.warning('请选择文件')
        return
      }
      const fd = new FormData()
      fd.append('file', uploadFile.value)
      if (uploadForm.value.title) fd.append('title', uploadForm.value.title)
      fd.append('category', uploadForm.value.category)
      await uploadKnowledgeFile(fd)
    } else {
      if (!uploadForm.value.content?.trim()) {
        ElMessage.warning('请填写内容')
        return
      }
      await uploadKnowledgeText({
        title: uploadForm.value.title || 'untitled',
        content: uploadForm.value.content,
        category: uploadForm.value.category
      })
    }
    ElMessage.success('已入库')
    showUpload.value = false
    uploadForm.value = { title: '', category: 'general', content: '' }
    uploadFile.value = null
    await refreshAll()
  } finally {
    uploading.value = false
  }
}

async function removeDoc (row) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」及其全部分块？`, '删除文档', { type: 'warning' })
    await deleteKnowledgeDocument(row.documentId)
    ElMessage.success('已删除')
    await refreshAll()
  } catch (e) {
    if (e !== 'cancel') {
      // api interceptor already toasts
    }
  }
}

async function runSearch () {
  if (!searchQuery.value?.trim()) {
    ElMessage.warning('请输入检索问题')
    return
  }
  searching.value = true
  searchedOnce.value = true
  try {
    searchHits.value = await searchKnowledge(searchQuery.value.trim(), 5) || []
  } catch {
    searchHits.value = []
  } finally {
    searching.value = false
  }
}

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.status-row {
  margin-bottom: 16px;
}

.status-card {
  margin-bottom: 12px;
}

.status-label {
  font-size: 12px;
  color: var(--ops-text-muted);
}

.status-value {
  font-size: 22px;
  font-weight: 600;
  margin: 6px 0;
}

.status-value.ok {
  color: var(--el-color-success);
}

.status-value.warn {
  color: var(--el-color-warning);
}

.status-meta {
  font-size: 12px;
  color: var(--ops-text-muted);
}

.conn-alert {
  margin-bottom: 16px;
}

.toolbar {
  margin-bottom: 12px;
  display: flex;
  gap: 8px;
}

.doc-table {
  width: 100%;
}

.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}

.search-panel .search-btn {
  margin-top: 12px;
}

.hits {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.hit-card {
  border: 1px solid var(--ops-border-soft);
}

.hit-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.hit-title {
  font-weight: 600;
}

.hit-meta {
  font-size: 12px;
  color: var(--ops-text-muted);
  margin-bottom: 8px;
}

.hit-content {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
</style>
