<template>
  <div class="ops-page profile-page" v-loading="loading">
    <OpsPageHeader
      :title="profileForm.username || '个人中心'"
      subtitle="管理资料、密码与 API Key，查看访问足迹与个人统计"
    >
      <template #actions>
        <el-tag size="large" :type="profileForm.role === 1 ? 'warning' : 'info'">{{ roleText }}</el-tag>
        <span class="profile-created">创建 {{ formatDateTime(profileForm.createTime) }}</span>
      </template>
    </OpsPageHeader>

    <div class="stats-grid">
      <article class="stat-card">
        <span>访问请求</span>
        <strong>{{ stats.auditRequestCount ?? '--' }}</strong>
        <small>个人接口与关键操作访问量</small>
      </article>
      <article class="stat-card">
        <span>活跃 API Key</span>
        <strong>{{ stats.activeApiKeyCount ?? 0 }}</strong>
        <small>{{ stats.hasApiKey ? '已存在可用 Key' : '尚未创建 API Key' }}</small>
      </article>
      <article class="stat-card">
        <span>当前鉴权方式</span>
        <strong>{{ stats.authMode || '--' }}</strong>
        <small>会话态与 API Key 态都会在足迹里留下记录</small>
      </article>
      <article class="stat-card">
        <span>最近活动</span>
        <strong>{{ stats.lastActivityAt ? formatDateTime(stats.lastActivityAt) : '--' }}</strong>
        <small>来源于访问足迹最新时间线</small>
      </article>
    </div>

    <div class="card-grid">
      <section class="panel-card">
        <div class="panel-head">
          <div>
            <h2>基础资料</h2>
            <p>只允许编辑自己的邮箱和企业微信用户标识。</p>
          </div>
          <el-tag type="success" v-if="profileDirty">已修改</el-tag>
        </div>
        <el-form label-position="top">
          <div class="two-col">
            <el-form-item label="用户名">
              <el-input v-model="profileForm.username" disabled />
            </el-form-item>
            <el-form-item label="角色">
              <el-input :model-value="roleText" disabled />
            </el-form-item>
          </div>
          <div class="two-col">
            <el-form-item label="邮箱">
              <el-input v-model="profileForm.email" />
            </el-form-item>
            <el-form-item label="企业微信用户 ID">
              <el-input v-model="profileForm.wechat_userid" />
            </el-form-item>
          </div>
          <div class="form-actions">
            <el-button @click="resetProfileForm" :disabled="!profileDirty || savingProfile">还原</el-button>
            <el-button type="primary" :loading="savingProfile" :disabled="!profileDirty" @click="saveProfile">保存资料</el-button>
          </div>
        </el-form>
      </section>

      <section class="panel-card">
        <div class="panel-head">
          <div>
            <h2>账号安全</h2>
            <p>修改密码会走旧密码校验，并刷新本地展示信息。</p>
          </div>
        </div>
        <el-form label-position="top">
          <el-form-item label="旧密码">
            <el-input v-model="passwordForm.oldPassword" type="password" show-password />
          </el-form-item>
          <div class="two-col">
            <el-form-item label="新密码">
              <el-input v-model="passwordForm.newPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
            </el-form-item>
          </div>
          <div class="form-actions">
            <el-button text @click="resetPasswordForm" :disabled="changingPassword">清空</el-button>
            <el-button type="primary" :loading="changingPassword" @click="changePasswordNow">修改密码</el-button>
          </div>
        </el-form>
      </section>

      <section class="panel-card">
        <div class="panel-head">
          <div>
            <h2>API Key 管理</h2>
            <p>创建或轮换时只展示一次完整明文；列表只保留名称、前缀和状态。</p>
          </div>
          <el-button type="primary" @click="apiKeyDialogVisible = true">新建 API Key</el-button>
        </div>

        <el-table :data="apiKeys" stripe>
          <el-table-column prop="name" label="名称" min-width="140" />
          <el-table-column prop="keyPrefix" label="前缀" min-width="160" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="lastUsedAt" label="最近使用" min-width="180">
            <template #default="{ row }">{{ formatDateTime(row.lastUsedAt) }}</template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" min-width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="rotateKey(row)" :disabled="row.status !== 'ACTIVE'">轮换</el-button>
              <el-button link type="danger" @click="revokeKey(row)" :disabled="row.status !== 'ACTIVE'">停用</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <section class="panel-card">
      <div class="panel-head">
        <div>
          <h2>访问足迹与个人统计</h2>
          <p>继续复用审计轨迹，聚合登录、接口访问和关键操作时间线。</p>
        </div>
        <el-button :loading="trailLoading" @click="loadTrail(accessTrail.page, accessTrail.pageSize)">刷新足迹</el-button>
      </div>
      <el-table :data="accessTrail.items" stripe>
        <el-table-column prop="created_at" label="时间" min-width="190">
          <template #default="{ row }">{{ formatDateTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="动作" min-width="120">
          <template #default="{ row }">{{ formatTrailAction(row) }}</template>
        </el-table-column>
        <el-table-column prop="path" label="路径" min-width="280" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="duration_ms" label="耗时(ms)" width="110" />
        <el-table-column prop="remote_ip" label="来源 IP" min-width="140" />
      </el-table>
      <div class="trail-footer">
        <span>共 {{ accessTrail.total }} 条记录</span>
        <el-pagination
          layout="prev, pager, next"
          :total="accessTrail.total"
          :page-size="accessTrail.pageSize"
          :current-page="accessTrail.page"
          @current-change="page => loadTrail(page, accessTrail.pageSize)"
        />
      </div>
    </section>

    <el-dialog v-model="apiKeyDialogVisible" title="创建 API Key" width="460px">
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="newApiKeyName" placeholder="例如：Ops Readonly Key" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="apiKeyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="apiKeyCreating" @click="createApiKeyNow">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="apiKeyRevealVisible" title="请立即保存这个 API Key" width="560px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="完整明文仅展示这一次"
        description="列表中只会保留前缀和元信息；如果现在错过，后续只能轮换生成新的 Key。"
      />
      <el-input
        class="key-output"
        :model-value="revealedApiKey"
        readonly
      >
        <template #append>
          <el-button @click="copyRevealedKey">复制</el-button>
        </template>
      </el-input>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeUserPassword,
  createUserApiKey,
  getAccessTrail,
  getUserApiKeys,
  getUserInfo,
  getUserStats,
  revokeUserApiKey,
  rotateUserApiKey,
  updateUserInfo
} from '../api'
import { formatDateTime } from '../utils/formatDate.js'
import OpsPageHeader from './OpsPageHeader.vue'

const emit = defineEmits(['profile-updated'])

const loading = ref(false)
const savingProfile = ref(false)
const changingPassword = ref(false)
const trailLoading = ref(false)
const apiKeyCreating = ref(false)
const apiKeyDialogVisible = ref(false)
const apiKeyRevealVisible = ref(false)
const revealedApiKey = ref('')
const newApiKeyName = ref('')

const profileForm = reactive({
  userId: null,
  username: '',
  role: 0,
  createTime: '',
  email: '',
  wechat_userid: ''
})
const profileBaseline = ref('')

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const stats = reactive({
  auditRequestCount: 0,
  activeApiKeyCount: 0,
  hasApiKey: false,
  authMode: '',
  lastActivityAt: ''
})

const apiKeys = ref([])
const accessTrail = reactive({
  page: 1,
  pageSize: 10,
  total: 0,
  items: []
})

const roleText = computed(() => profileForm.role === 1 ? '管理员' : '普通用户')
const profileDirty = computed(() => profileBaseline.value && profileBaseline.value !== JSON.stringify(profileEditablePayload()))

function profileEditablePayload() {
  return {
    email: (profileForm.email || '').trim(),
    wechat_userid: (profileForm.wechat_userid || '').trim()
  }
}

async function loadProfile() {
  const data = await getUserInfo()
  profileForm.userId = data.userId
  profileForm.username = data.username || ''
  profileForm.role = data.role ?? 0
  profileForm.createTime = data.createTime || ''
  profileForm.email = data.email || ''
  profileForm.wechat_userid = data.wechat_userid || ''
  profileBaseline.value = JSON.stringify(profileEditablePayload())
  syncLocalUser(data)
}

async function loadStats() {
  const data = await getUserStats()
  Object.assign(stats, data || {})
}

async function loadApiKeys() {
  apiKeys.value = await getUserApiKeys()
}

async function loadTrail(page = 1, pageSize = accessTrail.pageSize) {
  trailLoading.value = true
  try {
    const data = await getAccessTrail(page, pageSize)
    accessTrail.page = data.page || page
    accessTrail.pageSize = data.pageSize || pageSize
    accessTrail.total = data.total || 0
    accessTrail.items = data.items || []
  } finally {
    trailLoading.value = false
  }
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([
      loadProfile(),
      loadStats(),
      loadApiKeys(),
      loadTrail(1, accessTrail.pageSize)
    ])
  } finally {
    loading.value = false
  }
}

function resetProfileForm() {
  if (!profileBaseline.value) return
  const snapshot = JSON.parse(profileBaseline.value)
  profileForm.email = snapshot.email || ''
  profileForm.wechat_userid = snapshot.wechat_userid || ''
}

function resetPasswordForm() {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
}

async function saveProfile() {
  savingProfile.value = true
  try {
    const updated = await updateUserInfo(profileEditablePayload())
    profileForm.email = updated.email || ''
    profileForm.wechat_userid = updated.wechat_userid || ''
    profileBaseline.value = JSON.stringify(profileEditablePayload())
    syncLocalUser(updated)
    ElMessage.success('个人资料已更新')
  } catch (error) {
    ElMessage.error(error.message || '保存资料失败')
  } finally {
    savingProfile.value = false
  }
}

async function changePasswordNow() {
  if (!passwordForm.oldPassword || !passwordForm.newPassword || !passwordForm.confirmPassword) {
    ElMessage.warning('请完整填写密码字段')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changingPassword.value = true
  try {
    await changeUserPassword({ ...passwordForm })
    resetPasswordForm()
    await Promise.all([loadProfile(), loadStats()])
    ElMessage.success('密码已更新')
  } catch (error) {
    ElMessage.error(error.message || '修改密码失败')
  } finally {
    changingPassword.value = false
  }
}

async function createApiKeyNow() {
  apiKeyCreating.value = true
  try {
    const data = await createUserApiKey({ name: newApiKeyName.value.trim() || undefined })
    apiKeyDialogVisible.value = false
    newApiKeyName.value = ''
    revealedApiKey.value = data.plainTextApiKey || ''
    apiKeyRevealVisible.value = true
    await Promise.all([loadApiKeys(), loadStats()])
    ElMessage.success('API Key 已创建')
  } catch (error) {
    ElMessage.error(error.message || '创建 API Key 失败')
  } finally {
    apiKeyCreating.value = false
  }
}

async function rotateKey(row) {
  try {
    await ElMessageBox.confirm(`确定轮换「${row.name || row.keyName}」吗？旧 Key 会立即失效。`, '轮换 API Key', {
      type: 'warning'
    })
    const data = await rotateUserApiKey(row.id)
    revealedApiKey.value = data.plainTextApiKey || ''
    apiKeyRevealVisible.value = true
    await Promise.all([loadApiKeys(), loadStats()])
    ElMessage.success('API Key 已轮换')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '轮换 API Key 失败')
    }
  }
}

async function revokeKey(row) {
  try {
    await ElMessageBox.confirm(`确定停用「${row.name || row.keyName}」吗？`, '停用 API Key', {
      type: 'warning'
    })
    await revokeUserApiKey(row.id)
    await Promise.all([loadApiKeys(), loadStats()])
    ElMessage.success('API Key 已停用')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error.message || '停用 API Key 失败')
    }
  }
}

async function copyRevealedKey() {
  try {
    await navigator.clipboard.writeText(revealedApiKey.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

function formatTrailAction(row) {
  const method = String(row.method || '').toUpperCase()
  if (method === 'LOGIN') return '登录'
  if (method === 'LOGOUT') return '退出登录'
  return `${method} 请求`
}

function syncLocalUser(user) {
  const stored = JSON.parse(localStorage.getItem('user') || '{}')
  const merged = {
    ...stored,
    userId: user.userId ?? stored.userId,
    username: user.username ?? stored.username,
    role: user.role ?? stored.role
  }
  localStorage.setItem('user', JSON.stringify(merged))
  emit('profile-updated', merged)
}

loadAll()
</script>

<style scoped>
.profile-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.profile-created {
  font-size: 12px;
  color: var(--ops-text-subtle);
}

.stats-grid,
.card-grid {
  display: grid;
  gap: 14px;
}

.stats-grid {
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.card-grid {
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
}

.stat-card,
.panel-card {
  padding: 20px;
  border-radius: 20px;
  background: linear-gradient(180deg, #ffffff, #f8fafc);
  border: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.06);
}

.stat-card span {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #0f766e;
}

.stat-card strong {
  display: block;
  margin-top: 10px;
  font-size: 26px;
  color: #0f172a;
}

.stat-card small {
  display: block;
  margin-top: 8px;
  color: #475569;
  line-height: 1.55;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.panel-head h2 {
  margin: 0;
  font-size: 22px;
  color: #0f172a;
}

.panel-head p {
  margin: 8px 0 0;
  color: #475569;
  line-height: 1.6;
}

.two-col {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.trail-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
  gap: 12px;
}

.key-output {
  margin-top: 16px;
}

@media (max-width: 960px) {
  .panel-head,
  .trail-footer {
    flex-direction: column;
    align-items: flex-start;
  }

  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
