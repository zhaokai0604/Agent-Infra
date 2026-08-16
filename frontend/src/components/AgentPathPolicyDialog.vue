<template>
  <el-dialog
    v-model="visible"
    title="路径白名单"
    width="720px"
    destroy-on-close
    @open="loadPolicy"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 12px"
      title="安全提示"
      description="仅管理员应修改。保存后立即生效（写入 agent-path-policy-overrides.json）。禁止路径片段仍由 application.yml 的 deniedSubstrings 约束，无法在此放宽。"
    />

    <div v-loading="loading">
      <p class="meta">
        当前平台：<strong>{{ form.platform || '-' }}</strong> · 策略版本：<code>{{ form.policyVersion }}</code>
        <span v-if="form.overrideFileExists"> · 已存在本地覆盖文件</span>
      </p>

      <el-form label-position="top">
        <el-form-item label="可读 / 扫描路径">
          <div v-for="(_, idx) in form.readPrefixes" :key="'r' + idx" class="row">
            <el-input v-model="form.readPrefixes[idx]" placeholder="/var/log" />
            <el-button type="danger" link @click="removeItem('readPrefixes', idx)">删除</el-button>
          </div>
          <el-button type="primary" link @click="addItem('readPrefixes', defaultPathHint)">+ 添加路径</el-button>
        </el-form-item>

        <el-form-item label="临时目录清理白名单">
          <div v-for="(_, idx) in form.cleanRoots" :key="'c' + idx" class="row">
            <el-input v-model="form.cleanRoots[idx]" placeholder="/tmp" />
            <el-button type="danger" link @click="removeItem('cleanRoots', idx)">删除</el-button>
          </div>
          <el-button type="primary" link @click="addItem('cleanRoots', '/tmp')">+ 添加路径</el-button>
        </el-form-item>

        <el-form-item label="日志清理白名单">
          <div v-for="(_, idx) in form.logCleanupRoots" :key="'l' + idx" class="row">
            <el-input v-model="form.logCleanupRoots[idx]" placeholder="/var/log" />
            <el-button type="danger" link @click="removeItem('logCleanupRoots', idx)">删除</el-button>
          </div>
          <el-button type="primary" link @click="addItem('logCleanupRoots', '/var/log')">+ 添加路径</el-button>
        </el-form-item>

        <el-form-item label="可重启服务名">
          <div v-for="(_, idx) in form.serviceRestartAllowlist" :key="'s' + idx" class="row">
            <el-input v-model="form.serviceRestartAllowlist[idx]" placeholder="nginx" />
            <el-button type="danger" link @click="removeItem('serviceRestartAllowlist', idx)">删除</el-button>
          </div>
          <el-button type="primary" link @click="addItem('serviceRestartAllowlist', 'nginx')">+ 添加服务</el-button>
        </el-form-item>

        <el-form-item label="禁止片段（只读，来自服务端配置）">
          <el-tag v-for="d in form.deniedSubstrings" :key="d" size="small" style="margin: 4px">{{ d }}</el-tag>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="savePolicy">保存并生效</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAgentPathPolicy, saveAgentPathPolicy } from '../api'

const visible = ref(false)
const loading = ref(false)
const saving = ref(false)

const form = reactive({
  platform: '',
  policyVersion: '',
  overrideFileExists: false,
  readPrefixes: [],
  cleanRoots: [],
  logCleanupRoots: [],
  serviceRestartAllowlist: [],
  deniedSubstrings: []
})

const defaultPathHint = computed(() => (form.platform === 'windows' ? 'C:/logs' : '/var/log'))

function open() {
  visible.value = true
}

function addItem(field, sample) {
  if (!Array.isArray(form[field])) {
    form[field] = []
  }
  form[field].push(sample || '')
}

function removeItem(field, idx) {
  form[field].splice(idx, 1)
}

async function loadPolicy() {
  loading.value = true
  try {
    const data = await getAgentPathPolicy()
    form.platform = data.platform || ''
    form.policyVersion = data.policyVersion || ''
    form.overrideFileExists = !!data.overrideFileExists
    form.readPrefixes = [...(data.readPrefixes || [])]
    form.cleanRoots = [...(data.cleanRoots || [])]
    form.logCleanupRoots = [...(data.logCleanupRoots || [])]
    form.serviceRestartAllowlist = [...(data.serviceRestartAllowlist || [])]
    form.deniedSubstrings = [...(data.deniedSubstrings || [])]
    if (form.readPrefixes.length === 0) form.readPrefixes.push(defaultPathHint.value)
    if (form.cleanRoots.length === 0) form.cleanRoots.push(form.platform === 'windows' ? 'C:/Temp' : '/tmp')
    if (form.logCleanupRoots.length === 0) {
      form.logCleanupRoots.push(form.platform === 'windows' ? 'C:/Windows/Logs' : '/var/log')
    }
  } catch (e) {
    ElMessage.error('加载白名单失败: ' + (e.message || e))
  } finally {
    loading.value = false
  }
}

async function savePolicy() {
  saving.value = true
  try {
    const payload = {
      readPrefixes: form.readPrefixes.map((s) => s.trim()).filter(Boolean),
      cleanRoots: form.cleanRoots.map((s) => s.trim()).filter(Boolean),
      logCleanupRoots: form.logCleanupRoots.map((s) => s.trim()).filter(Boolean),
      serviceRestartAllowlist: form.serviceRestartAllowlist.map((s) => s.trim()).filter(Boolean)
    }
    const data = await saveAgentPathPolicy(payload)
    ElMessage.success(data?.message || '白名单已保存')
    visible.value = false
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || e))
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.meta {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}
.row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.row .el-input {
  flex: 1;
}
</style>
