<template>
  <div class="ops-page config-page">
    <OpsPageHeader
      title="系统配置与效果"
      subtitle="集中管理运行策略、日志采集、AI 接入与安全白名单；保存后的热生效/重启影响见下方摘要"
    >
      <template #actions>
        <el-button :icon="Refresh" @click="reloadAll" :loading="loading">刷新视图</el-button>
        <el-button type="primary" :icon="Download" @click="exportJsonSnapshot">导出 JSON</el-button>
        <el-button type="success" :icon="Document" @click="exportPdfReport">导出 PDF</el-button>
      </template>
    </OpsPageHeader>

    <section class="summary-grid">
      <article class="summary-card">
        <span class="summary-label">平台</span>
        <strong>{{ platformSummary }}</strong>
        <small>{{ activeProfileLine }}</small>
      </article>
      <article class="summary-card">
        <span class="summary-label">当前角色</span>
        <strong>{{ viewerRoleText }}</strong>
        <small>{{ viewer?.editable ? '可保存系统配置' : '仅查看，不可修改' }}</small>
      </article>
      <article class="summary-card">
        <span class="summary-label">热生效项</span>
        <strong>{{ hotItemCount }}</strong>
        <small>保存后立即更新运行时内存态</small>
      </article>
      <article class="summary-card">
        <span class="summary-label">待重启项</span>
        <strong>{{ restartItemCount }}</strong>
        <small>{{ needsRestart.length }} 项已有待生效变更</small>
      </article>
      <article class="summary-card">
        <span class="summary-label">AI 接入</span>
        <strong>{{ aiRuntimeModel }}</strong>
        <small>{{ aiRuntimeProviderLine }} · Key {{ aiKeyStatusText }}</small>
      </article>
    </section>

    <section class="bootstrap-grid">
      <article class="bootstrap-card">
        <div class="bootstrap-card__head">
          <div>
            <span class="summary-label">启动探测状态</span>
            <strong>{{ bootstrapPlatformLine }}</strong>
          </div>
          <el-tag :type="bootstrapStatus?.corrected ? 'warning' : 'success'" effect="plain">
            {{ bootstrapStatus?.corrected ? '已自动纠正' : '无需纠正' }}
          </el-tag>
        </div>
        <small class="bootstrap-card__meta">
          最近执行：{{ formatDateTime(bootstrapStatus?.lastRunAt) }} · 指纹：{{ bootstrapStatus?.platformFingerprint || '--' }}
        </small>
        <div class="bootstrap-chip-row">
          <el-tag
            v-for="([name]) in bootstrapCapabilityEntries"
            :key="name"
            size="small"
            effect="plain"
          >
            {{ name }}
          </el-tag>
          <span v-if="!bootstrapCapabilityEntries.length" class="bootstrap-empty">暂无能力摘要</span>
        </div>
      </article>

      <article class="bootstrap-card">
        <div class="bootstrap-card__head">
          <div>
            <span class="summary-label">最近自动纠正</span>
            <strong>{{ bootstrapChangedKeys.length }}</strong>
          </div>
          <el-button
            v-if="viewer?.editable"
            type="primary"
            size="small"
            plain
            :loading="bootstrapLoading"
            @click="reconcileBootstrapNow"
          >
            手动重新探测
          </el-button>
        </div>
        <small class="bootstrap-card__meta">
          自动生成 {{ bootstrapGeneratedKeys.length }} 项 · 配置目录 {{ bootstrapStatus?.configDir || '--' }}
        </small>
        <div class="bootstrap-list">
          <div v-if="bootstrapChangedKeys.length" class="bootstrap-list__row">
            <span class="bootstrap-list__label">已纠正</span>
            <div class="bootstrap-chip-row">
              <el-tag v-for="item in bootstrapChangedKeys" :key="item" size="small" type="warning" effect="plain">
                {{ item }}
              </el-tag>
            </div>
          </div>
          <div v-if="bootstrapGeneratedKeys.length" class="bootstrap-list__row">
            <span class="bootstrap-list__label">自动生成</span>
            <div class="bootstrap-chip-row">
              <el-tag v-for="item in bootstrapGeneratedKeys" :key="item" size="small" type="success" effect="plain">
                {{ item }}
              </el-tag>
            </div>
          </div>
          <div v-if="!bootstrapChangedKeys.length && !bootstrapGeneratedKeys.length" class="bootstrap-empty">
            当前没有待展示的自动纠正项
          </div>
        </div>
      </article>
    </section>

    <el-alert
      v-if="!viewer?.editable"
      class="status-banner"
      type="info"
      :closable="false"
      show-icon
      title="当前为只读态"
      description="系统配置页全员可看，只有管理员可以编辑和保存。你现在看到的是当前有效值与待重启变更摘要。"
    />

    <el-alert
      v-else
      class="status-banner"
      type="warning"
      :closable="false"
      show-icon
      title="管理员编辑态"
      :description="isDirty ? '你有未保存的配置改动。热生效项会立即更新，重启项会写入本地覆盖文件并标记待重启。' : '可以直接修改并保存配置；页面会明确标出热生效项和需重启项。'"
    />

    <el-alert
      v-if="runtimeForm.dryRunGlobal"
      class="status-banner"
      type="warning"
      :closable="false"
      show-icon
      title="全局演练模式已开启"
      description="写操作（清理、重启等）仅预览，不会真正落地。可在下方「运行策略 → 全局演练模式」关闭。"
    />

    <el-alert
      v-if="!aiKeyConfigured"
      class="status-banner"
      type="info"
      :closable="false"
      show-icon
      title="AI 未配置"
      description="智能对话暂不可用；运维工具与巡检仍可正常使用。可在下方「AI 接入」填写 API Key。"
    />

    <div v-if="messages.length" class="message-stack">
      <el-alert
        v-for="msg in messages"
        :key="msg"
        type="success"
        :closable="false"
        show-icon
        :title="msg"
      />
    </div>

    <div v-loading="loading" class="config-layout">
      <section ref="runtimeRef" class="config-card config-card--compact runtime-panel">
        <div class="path-policy-bar">
          <h2>运行策略</h2>
          <el-tag type="success" size="small">HOT</el-tag>
          <span class="path-policy-meta">巡检 · 自愈 · 自动修复 · 保存即生效</span>
        </div>

        <div class="runtime-toolbar">
          <label class="runtime-switch">
            <span>自动修复</span>
            <el-switch v-model="runtimeForm.autoRemediationEnabled" :disabled="!viewer?.editable" size="small" />
          </label>
          <label class="runtime-switch">
            <span>全局演练模式</span>
            <el-switch v-model="runtimeForm.dryRunGlobal" :disabled="!viewer?.editable" size="small" />
          </label>
          <div class="runtime-field">
            <span class="runtime-field-label">Ping 目标</span>
            <el-input v-model="runtimeForm.pingTarget" size="small" :disabled="!viewer?.editable" placeholder="如 114.114.114.114" />
          </div>
          <div class="runtime-field runtime-field--mode">
            <span class="runtime-field-label">修复模式</span>
            <el-select v-model="runtimeForm.autoRemediationMode" size="small" :disabled="!viewer?.editable">
              <el-option label="混合模式" value="HYBRID" />
              <el-option label="先确认后执行" value="CONFIRM_FIRST" />
              <el-option label="自动执行" value="IMMEDIATE" />
            </el-select>
          </div>
        </div>

        <div class="runtime-dual">
          <div class="runtime-mini-block">
            <div class="runtime-mini-head">
              <span>巡检根目录</span>
              <span class="runtime-mini-count">{{ runtimeForm.patrolInspectRoots.length }} 项</span>
              <el-button
                v-if="viewer?.editable"
                link
                type="primary"
                size="small"
                @click="runtimeForm.patrolInspectRoots.push(defaultInspectRoot)"
              >+</el-button>
            </div>
            <div class="runtime-chip-line">
              <template v-if="viewer?.editable">
                <div v-for="(item, index) in runtimeForm.patrolInspectRoots" :key="`in-${index}`" class="runtime-chip-edit">
                  <el-input v-model="runtimeForm.patrolInspectRoots[index]" size="small" />
                  <el-button link type="danger" size="small" @click="removeListItem(runtimeForm.patrolInspectRoots, index)">×</el-button>
                </div>
              </template>
              <el-tag v-for="(item, index) in runtimeForm.patrolInspectRoots" v-else :key="`in-ro-${index}`" size="small" effect="plain">{{ item }}</el-tag>
            </div>
          </div>
          <div class="runtime-mini-block">
            <div class="runtime-mini-head">
              <span>健康检查端口</span>
              <span class="runtime-mini-count">{{ runtimeForm.healthCheckPorts.length }} 项</span>
              <el-button v-if="viewer?.editable" link type="primary" size="small" @click="runtimeForm.healthCheckPorts.push(8080)">+</el-button>
            </div>
            <div class="runtime-chip-line">
              <template v-if="viewer?.editable">
                <div v-for="(item, index) in runtimeForm.healthCheckPorts" :key="`port-${index}`" class="runtime-chip-edit runtime-chip-edit--port">
                  <el-input-number
                    v-model="runtimeForm.healthCheckPorts[index]"
                    :min="1"
                    :max="65535"
                    size="small"
                    controls-position="right"
                    :disabled="!viewer?.editable"
                  />
                  <el-button link type="danger" size="small" @click="removeListItem(runtimeForm.healthCheckPorts, index)">×</el-button>
                </div>
              </template>
              <el-tag v-for="(item, index) in runtimeForm.healthCheckPorts" v-else :key="`port-ro-${index}`" size="small" effect="plain">{{ item }}</el-tag>
            </div>
          </div>
        </div>

        <div class="runtime-metrics-grid">
          <div v-for="metric in runtimeMetrics" :key="metric.key" class="runtime-metric-cell">
            <span class="runtime-metric-label">{{ metric.label }}</span>
            <el-input-number
              v-model="runtimeForm[metric.key]"
              :min="metric.min"
              :max="metric.max"
              :step="metric.step"
              :precision="metric.precision"
              size="small"
              controls-position="right"
              :disabled="!viewer?.editable"
            />
          </div>
        </div>
      </section>

      <section ref="collectorRef" class="config-card">
        <div class="card-head">
          <div>
            <h2>日志采集</h2>
            <p>会保存到本地覆盖文件，应用重启后才会重新实例化采集器并生效。</p>
          </div>
          <el-tag type="warning">RESTART</el-tag>
        </div>
        <el-form label-position="top" class="config-form">
          <div class="split-grid">
            <el-form-item label="采集根目录">
              <el-input v-model="collectorForm.fileRoot" :disabled="!viewer?.editable" />
            </el-form-item>
            <el-form-item label="采集扩展名">
              <el-input v-model="collectorForm.includeExtensions" :disabled="!viewer?.editable" />
            </el-form-item>
          </div>
          <el-form-item label="排除目录">
            <el-input v-model="collectorForm.excludeDirectories" :disabled="!viewer?.editable" />
          </el-form-item>
          <div class="switch-grid">
            <label class="switch-card">
              <span>启用网络采集</span>
              <el-switch v-model="collectorForm.networkEnabled" :disabled="!viewer?.editable" />
            </label>
            <label class="switch-card">
              <span>启用数据库采集</span>
              <el-switch v-model="collectorForm.dbEnabled" :disabled="!viewer?.editable" />
            </label>
          </div>
          <div class="split-grid">
            <el-form-item label="网络采集端口">
              <el-input-number v-model="collectorForm.networkPort" :min="1" :max="65535" :disabled="!viewer?.editable" />
            </el-form-item>
            <el-form-item label="网络采集协议">
              <el-select v-model="collectorForm.networkProtocol" :disabled="!viewer?.editable">
                <el-option label="UDP" value="UDP" />
                <el-option label="TCP" value="TCP" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="数据库采集 SQL">
            <el-input v-model="collectorForm.dbQuery" type="textarea" :rows="4" :disabled="!viewer?.editable" />
          </el-form-item>
        </el-form>
      </section>

      <section ref="aiRef" class="config-card">
        <div class="card-head">
          <div>
            <h2>AI 接入</h2>
            <p>当前运行值来自配置文件合并结果；修改后需<strong>重启后端</strong>生效。AI 接口与模型可在下方填写。</p>
          </div>
          <el-tag type="warning">RESTART</el-tag>
        </div>
        <el-form label-position="top" class="config-form">
          <el-form-item label="AI Base URL">
            <el-input v-model="aiForm.baseUrl" placeholder="https://api.deepseek.com" :disabled="!viewer?.editable" />
          </el-form-item>
          <div class="split-grid">
            <el-form-item label="聊天模型">
              <el-input v-model="aiForm.chatModel" placeholder="deepseek-chat" :disabled="!viewer?.editable" />
            </el-form-item>
            <el-form-item label="Embedding 模型">
              <el-input v-model="aiForm.embeddingModel" placeholder="deepseek-chat" :disabled="!viewer?.editable" />
            </el-form-item>
          </div>
          <el-form-item label="AI API Key">
            <div class="secret-row">
              <el-input
                v-model="aiForm.aiApiKey"
                show-password
                :disabled="!viewer?.editable || !canEditSecrets"
                :placeholder="aiKeyPlaceholder"
              />
              <div class="secret-meta">
                <el-tag :type="aiKeyConfigured ? 'success' : 'info'">{{ aiKeyStatusText }}</el-tag>
                <span v-if="!canEditSecrets" class="secret-tip">未设置 APP_CONFIG_SECRET，当前不可写入敏感项。</span>
              </div>
            </div>
          </el-form-item>
        </el-form>
      </section>

      <section ref="pathPolicyRef" class="config-card config-card--compact path-policy-panel">
        <div class="path-policy-bar">
          <h2>安全白名单</h2>
          <el-tag type="success" size="small">HOT</el-tag>
          <span class="path-policy-meta">{{ pathPolicySummary }}</span>
          <el-dropdown v-if="viewer?.editable" trigger="click" @command="addPathPolicyEntry">
            <el-button type="primary" link size="small">+ 新增</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="readPrefixes">可读/扫描路径</el-dropdown-item>
                <el-dropdown-item command="cleanRoots">临时目录清理</el-dropdown-item>
                <el-dropdown-item command="logCleanupRoots">日志清理路径</el-dropdown-item>
                <el-dropdown-item command="serviceRestartAllowlist">可重启服务</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <el-table
          :data="pathPolicyRows"
          size="small"
          stripe
          class="path-policy-table"
          :max-height="pathPolicyTableHeight"
          empty-text="暂无白名单条目"
        >
          <el-table-column prop="label" label="类别" width="100" />
          <el-table-column label="路径 / 服务" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <el-input
                v-if="viewer?.editable"
                v-model="pathPolicyForm[row.key][row.index]"
                size="small"
                class="path-policy-input"
              />
              <span v-else class="path-cell">{{ row.value }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="viewer?.editable" label="" width="40" align="center" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removePathPolicyRow(row)">×</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="pathPolicyForm.deniedSubstrings.length" class="denied-inline">
          <span class="denied-inline-label">禁止片段</span>
          <el-tag
            v-for="item in pathPolicyForm.deniedSubstrings"
            :key="item"
            size="small"
            type="danger"
            effect="plain"
          >{{ item }}</el-tag>
        </div>
      </section>

      <section class="config-card effect-card">
        <div class="card-head">
          <div>
            <h2>保存后对照</h2>
            <p>仅展示与配置变更相关的巡检快照，便于保存前后比对；完整评分与安全探针请到专用页面查看。</p>
          </div>
          <el-button text @click="loadEffects" :loading="effectLoading">刷新快照</el-button>
        </div>
        <div class="effect-grid effect-grid--single">
          <article class="effect-panel">
            <span class="panel-label">最近巡检关联快照</span>
            <strong>{{ patrolSnapshot?.timestamp || '暂无数据' }}</strong>
            <p>磁盘 {{ numberOrDash(patrolSnapshot?.diskUsagePct) }}% · CPU {{ numberOrDash(patrolSnapshot?.cpuUsagePct) }}% · 内存 {{ numberOrDash(patrolSnapshot?.memoryUsagePct) }}%</p>
          </article>
        </div>
        <div class="effect-detail-grid">
          <div class="detail-box">
            <h3>巡检热点</h3>
            <ul>
              <li v-for="item in hotspotPreview" :key="item.path">{{ item.path }} · {{ numberOrDash(item.approxMiB) }} MiB</li>
              <li v-if="!hotspotPreview.length">暂无目录热点。</li>
            </ul>
          </div>
          <div class="detail-box effect-links">
            <h3>更多效果数据</h3>
            <p class="effect-links-hint">下列内容已在其它 Tab 完整展示，此处不再重复拉取。</p>
            <div class="effect-link-actions">
              <el-button type="primary" link @click="goToTab('audit')">统一审计中心（Tab 3）</el-button>
              <el-button type="primary" link @click="goToTab('security-cockpit')">安全驾驶舱</el-button>
            </div>
            <p class="effect-links-sub">顶栏「安全自检 / 驾驶舱」可查看护栏探针与策略回放。</p>
          </div>
        </div>
      </section>
    </div>

    <div v-if="viewer?.editable" class="save-bar">
      <div class="save-copy">
        <strong>{{ isDirty ? '有未保存改动' : '配置已同步' }}</strong>
        <span>{{ isDirty ? '点击保存后，热生效项会立即更新；需重启项会写入本地覆盖文件。' : '当前页面展示的是最新保存结果。' }}</span>
      </div>
      <div class="save-actions">
        <el-button @click="reloadConfig" :disabled="saving">还原到最新值</el-button>
        <el-button type="primary" :loading="saving" :disabled="!isDirty" @click="saveAll">保存配置</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Download, Refresh } from '@element-plus/icons-vue'
import {
  getPatrolCorrelationLatest,
  reconcileSystemBootstrap,
  getSystemConfigEffective,
  saveSystemConfigEffective
} from '../api'
import OpsPageHeader from './OpsPageHeader.vue'

const loading = ref(false)
const saving = ref(false)
const effectLoading = ref(false)
const configPayload = ref(null)
const viewer = ref(null)
const messages = ref([])
const needsRestart = ref([])
const savedAt = ref('')
const patrolSnapshot = ref(null)
const bootstrapStatus = ref(null)
const bootstrapLoading = ref(false)
const runtimeRef = ref(null)
const collectorRef = ref(null)
const aiRef = ref(null)
const pathPolicyRef = ref(null)
const dirtyBaseline = ref('')

const runtimeForm = reactive({
  patrolInspectRoots: [],
  healthCheckPorts: [],
  pingTarget: '',
  autoRemediationEnabled: false,
  autoRemediationMode: 'HYBRID',
  dryRunGlobal: false,
  patrolDiskWarnPercent: 80,
  patrolCpuWarnPercent: 85,
  anomalySpikeFactor: 2,
  errorAlarmMin: 3,
  autoRiskPatrolAutoMax: 6,
  autoProposeTempCleanDiskMin: 80,
  autoProposeLogCleanDiskMin: 85
})

const runtimeMetrics = [
  { key: 'patrolDiskWarnPercent', label: '磁盘阈值%', min: 1, max: 100, step: 1, precision: 0 },
  { key: 'patrolCpuWarnPercent', label: 'CPU阈值%', min: 1, max: 100, step: 1, precision: 0 },
  { key: 'anomalySpikeFactor', label: '突增倍率', min: 1, max: 20, step: 0.1, precision: 1 },
  { key: 'errorAlarmMin', label: '告警阈值', min: 1, max: 999, step: 1, precision: 0 },
  { key: 'autoRiskPatrolAutoMax', label: '风险阈值', min: 0.1, max: 20, step: 0.1, precision: 1 },
  { key: 'autoProposeTempCleanDiskMin', label: '临时清理%', min: 1, max: 100, step: 1, precision: 0 },
  { key: 'autoProposeLogCleanDiskMin', label: '日志清理%', min: 1, max: 100, step: 1, precision: 0 }
]

const collectorForm = reactive({
  fileRoot: '',
  includeExtensions: '',
  excludeDirectories: '',
  networkEnabled: false,
  networkPort: 514,
  networkProtocol: 'UDP',
  dbEnabled: false,
  dbQuery: ''
})

const aiForm = reactive({
  baseUrl: '',
  chatModel: '',
  embeddingModel: '',
  aiApiKey: ''
})

const pathPolicyForm = reactive({
  readPrefixes: [],
  cleanRoots: [],
  logCleanupRoots: [],
  serviceRestartAllowlist: [],
  deniedSubstrings: []
})

const PATH_POLICY_GROUPS = [
  { key: 'readPrefixes', label: '可读/扫描' },
  { key: 'cleanRoots', label: '临时清理' },
  { key: 'logCleanupRoots', label: '日志清理' },
  { key: 'serviceRestartAllowlist', label: '服务重启' }
]

const pathPolicyRows = computed(() => {
  const rows = []
  for (const group of PATH_POLICY_GROUPS) {
    const list = pathPolicyForm[group.key] || []
    list.forEach((value, index) => {
      rows.push({
        key: group.key,
        label: group.label,
        index,
        value
      })
    })
  }
  return rows
})

const pathPolicySummary = computed(() =>
  PATH_POLICY_GROUPS
    .map(g => `${g.label} ${(pathPolicyForm[g.key] || []).length}`)
    .join(' · ')
)

const pathPolicyTableHeight = computed(() => {
  const n = pathPolicyRows.value.length
  if (n <= 6) return undefined
  return Math.min(280, 36 + n * 32)
})

function removePathPolicyRow(row) {
  const list = pathPolicyForm[row.key]
  if (Array.isArray(list)) list.splice(row.index, 1)
}

function addPathPolicyEntry(key) {
  const defaults = {
    readPrefixes: defaultInspectRoot.value,
    cleanRoots: defaultTempRoot.value,
    logCleanupRoots: defaultLogRoot.value,
    serviceRestartAllowlist: 'nginx'
  }
  if (!Array.isArray(pathPolicyForm[key])) return
  pathPolicyForm[key].push(defaults[key] || '')
}

const defaultInspectRoot = computed(() => isWindowsPlatform.value ? 'D:/' : '/var/log')
const defaultTempRoot = computed(() => isWindowsPlatform.value ? 'C:/Temp' : '/tmp')
const defaultLogRoot = computed(() => isWindowsPlatform.value ? 'C:/Windows/Logs' : '/var/log')
const isWindowsPlatform = computed(() => String(configPayload.value?.platform?.osName || '').toLowerCase().includes('windows'))

function formatPlatformSummary(platform) {
  if (!platform || typeof platform !== 'object') return '未知平台'
  const raw = platform.summary
  if (typeof raw === 'string' && raw.trim()) return raw.trim()
  const meta = raw && typeof raw === 'object' ? raw : platform
  const osName = meta.osName || platform.osName || '未知系统'
  const osArch = meta.osArch || platform.osArch || ''
  const bits = [osName]
  if (osArch) bits.push(osArch)
  if (meta.kylin) bits.push('麒麟')
  if (meta.loongArch) bits.push('LoongArch')
  else if (meta.windows) bits.push('Windows')
  else if (meta.unixLike) bits.push('Unix')
  return bits.join(' · ')
}

const platformSummary = computed(() => formatPlatformSummary(configPayload.value?.platform))
const activeProfileLine = computed(() => {
  const profiles = configPayload.value?.platform?.activeProfiles || []
  return Array.isArray(profiles) && profiles.length ? profiles.join(' / ') : 'default'
})
const bootstrapPlatformLine = computed(() => {
  const platform = bootstrapStatus.value?.platform || {}
  const parts = [bootstrapStatus.value?.platformKey, platform.osName, platform.osArch]
    .map(item => String(item || '').trim())
    .filter(Boolean)
  return parts.length ? parts.join(' · ') : platformSummary.value
})
const bootstrapChangedKeys = computed(() => bootstrapStatus.value?.changedKeys || [])
const bootstrapGeneratedKeys = computed(() => bootstrapStatus.value?.autoGeneratedKeys || [])
const bootstrapCapabilityEntries = computed(() =>
  Object.entries(bootstrapStatus.value?.capabilities || {}).filter(([, enabled]) => enabled === true)
)
const viewerRoleText = computed(() => viewer.value?.role === 1 ? '管理员' : '普通用户')
const allItems = computed(() => {
  const groups = configPayload.value?.groups || {}
  return Object.values(groups).flatMap(group => group?.items || [])
})
const hotItemCount = computed(() => allItems.value.filter(item => item.applyMode === 'HOT').length)
const restartItemCount = computed(() => allItems.value.filter(item => item.applyMode === 'RESTART').length)
const aiKeyItem = computed(() => {
  const items = configPayload.value?.groups?.ai?.items || []
  return items.find(item => item.key === 'aiApiKey') || null
})
const aiKeyConfigured = computed(() => !!aiKeyItem.value?.configured)
const aiKeyStatusText = computed(() => {
  if (aiKeyItem.value?.pendingRestart) return '已保存待重启'
  return aiKeyConfigured.value ? '已配置' : '未配置'
})
const aiKeyPlaceholder = computed(() => aiKeyConfigured.value ? '已配置；如需轮换请重新输入新密钥' : '输入后仅会保存，不回显明文')

function aiItemValue(key) {
  const item = (configPayload.value?.groups?.ai?.items || []).find(i => i.key === key)
  return item?.value != null ? String(item.value).trim() : ''
}

function inferAiProvider(baseUrl) {
  const u = String(baseUrl || '').toLowerCase()
  if (u.includes('deepseek')) return 'DeepSeek'
  if (u.includes('dashscope') || u.includes('aliyun')) return '通义 DashScope'
  if (u.includes('openai.com')) return 'OpenAI'
  return 'OpenAI 兼容'
}

const aiRuntimeModel = computed(() => aiItemValue('chatModel') || '未配置模型')
const aiRuntimeProviderLine = computed(() => {
  const base = aiItemValue('baseUrl')
  if (!base) return inferAiProvider('')
  try {
    const host = new URL(base).host
    return `${inferAiProvider(base)}（${host}）`
  } catch {
    return `${inferAiProvider(base)}（${base}）`
  }
})

const canEditSecrets = computed(() => !messages.value.some(msg => msg.includes('APP_CONFIG_SECRET')))
const hotspotPreview = computed(() => patrolSnapshot.value?.diskHotspotsTop || [])
const isDirty = computed(() => dirtyBaseline.value && dirtyBaseline.value !== JSON.stringify(currentSnapshot()))

function groupIndex(groupKey) {
  const items = configPayload.value?.groups?.[groupKey]?.items || []
  return Object.fromEntries(items.map(item => [item.key, item]))
}

function assignFormState(data) {
  configPayload.value = data
  viewer.value = data.viewer || null
  messages.value = Array.isArray(data.messages) ? data.messages : []
  needsRestart.value = Array.isArray(data.needsRestart) ? data.needsRestart : []
  savedAt.value = data.savedAt || ''
  bootstrapStatus.value = data.bootstrap || null

  const runtime = groupIndex('runtime')
  runtimeForm.patrolInspectRoots = [...(runtime.patrolInspectRoots?.value || [])]
  runtimeForm.healthCheckPorts = [...(runtime.healthCheckPorts?.value || [])]
  runtimeForm.pingTarget = runtime.pingTarget?.value || ''
  runtimeForm.autoRemediationEnabled = !!runtime.autoRemediationEnabled?.value
  runtimeForm.autoRemediationMode = runtime.autoRemediationMode?.value || 'HYBRID'
  runtimeForm.dryRunGlobal = !!runtime.dryRunGlobal?.value
  runtimeForm.patrolDiskWarnPercent = Number(runtime.patrolDiskWarnPercent?.value || 80)
  runtimeForm.patrolCpuWarnPercent = Number(runtime.patrolCpuWarnPercent?.value || 85)
  runtimeForm.anomalySpikeFactor = Number(runtime.anomalySpikeFactor?.value || 2)
  runtimeForm.errorAlarmMin = Number(runtime.errorAlarmMin?.value || 3)
  runtimeForm.autoRiskPatrolAutoMax = Number(runtime.autoRiskPatrolAutoMax?.value || 6)
  runtimeForm.autoProposeTempCleanDiskMin = Number(runtime.autoProposeTempCleanDiskMin?.value || 80)
  runtimeForm.autoProposeLogCleanDiskMin = Number(runtime.autoProposeLogCleanDiskMin?.value || 85)

  const collector = groupIndex('collector')
  collectorForm.fileRoot = collector.fileRoot?.pendingValue ?? collector.fileRoot?.value ?? ''
  collectorForm.includeExtensions = collector.includeExtensions?.pendingValue ?? collector.includeExtensions?.value ?? ''
  collectorForm.excludeDirectories = collector.excludeDirectories?.pendingValue ?? collector.excludeDirectories?.value ?? ''
  collectorForm.networkEnabled = !!(collector.networkEnabled?.pendingValue ?? collector.networkEnabled?.value)
  collectorForm.networkPort = Number(collector.networkPort?.pendingValue ?? collector.networkPort?.value ?? 514)
  collectorForm.networkProtocol = collector.networkProtocol?.pendingValue ?? collector.networkProtocol?.value ?? 'UDP'
  collectorForm.dbEnabled = !!(collector.dbEnabled?.pendingValue ?? collector.dbEnabled?.value)
  collectorForm.dbQuery = collector.dbQuery?.pendingValue ?? collector.dbQuery?.value ?? ''

  const ai = groupIndex('ai')
  aiForm.baseUrl = ai.baseUrl?.pendingValue ?? ai.baseUrl?.value ?? ''
  aiForm.chatModel = ai.chatModel?.pendingValue ?? ai.chatModel?.value ?? ''
  aiForm.embeddingModel = ai.embeddingModel?.pendingValue ?? ai.embeddingModel?.value ?? ''
  aiForm.aiApiKey = ''

  const pathPolicy = groupIndex('pathPolicy')
  pathPolicyForm.readPrefixes = [...(pathPolicy.readPrefixes?.value || [])]
  pathPolicyForm.cleanRoots = [...(pathPolicy.cleanRoots?.value || [])]
  pathPolicyForm.logCleanupRoots = [...(pathPolicy.logCleanupRoots?.value || [])]
  pathPolicyForm.serviceRestartAllowlist = [...(pathPolicy.serviceRestartAllowlist?.value || [])]
  pathPolicyForm.deniedSubstrings = [...(pathPolicy.deniedSubstrings?.value || [])]

  dirtyBaseline.value = JSON.stringify(currentSnapshot())
}

function currentSnapshot() {
  return {
    runtime: {
      patrolInspectRoots: normalizeList(runtimeForm.patrolInspectRoots),
      healthCheckPorts: runtimeForm.healthCheckPorts.map(Number).filter(Boolean),
      pingTarget: runtimeForm.pingTarget.trim(),
      autoRemediationEnabled: !!runtimeForm.autoRemediationEnabled,
      autoRemediationMode: runtimeForm.autoRemediationMode,
      dryRunGlobal: !!runtimeForm.dryRunGlobal,
      patrolDiskWarnPercent: Number(runtimeForm.patrolDiskWarnPercent),
      patrolCpuWarnPercent: Number(runtimeForm.patrolCpuWarnPercent),
      anomalySpikeFactor: Number(runtimeForm.anomalySpikeFactor),
      errorAlarmMin: Number(runtimeForm.errorAlarmMin),
      autoRiskPatrolAutoMax: Number(runtimeForm.autoRiskPatrolAutoMax),
      autoProposeTempCleanDiskMin: Number(runtimeForm.autoProposeTempCleanDiskMin),
      autoProposeLogCleanDiskMin: Number(runtimeForm.autoProposeLogCleanDiskMin)
    },
    collector: {
      fileRoot: collectorForm.fileRoot.trim(),
      includeExtensions: collectorForm.includeExtensions.trim(),
      excludeDirectories: collectorForm.excludeDirectories.trim(),
      networkEnabled: !!collectorForm.networkEnabled,
      networkPort: Number(collectorForm.networkPort),
      networkProtocol: collectorForm.networkProtocol,
      dbEnabled: !!collectorForm.dbEnabled,
      dbQuery: collectorForm.dbQuery.trim()
    },
    ai: {
      baseUrl: aiForm.baseUrl.trim(),
      chatModel: aiForm.chatModel.trim(),
      embeddingModel: aiForm.embeddingModel.trim()
    },
    pathPolicy: {
      readPrefixes: normalizeList(pathPolicyForm.readPrefixes),
      cleanRoots: normalizeList(pathPolicyForm.cleanRoots),
      logCleanupRoots: normalizeList(pathPolicyForm.logCleanupRoots),
      serviceRestartAllowlist: normalizeList(pathPolicyForm.serviceRestartAllowlist)
    },
    secretOps: aiForm.aiApiKey.trim() ? { aiApiKey: aiForm.aiApiKey.trim() } : {}
  }
}

async function reloadConfig() {
  loading.value = true
  try {
    const data = await getSystemConfigEffective()
    assignFormState(data)
  } finally {
    loading.value = false
  }
}

async function loadEffects() {
  effectLoading.value = true
  try {
    patrolSnapshot.value = await getPatrolCorrelationLatest()
  } finally {
    effectLoading.value = false
  }
}

async function reloadAll() {
  await Promise.all([reloadConfig(), loadEffects()])
}

async function reconcileBootstrapNow() {
  if (!viewer.value?.editable) return
  bootstrapLoading.value = true
  try {
    const data = await reconcileSystemBootstrap()
    assignFormState(data)
    ElMessage.success('启动探测与平台派生配置已重新收敛')
  } catch (error) {
    ElMessage.error(error.message || '重新探测失败')
  } finally {
    bootstrapLoading.value = false
  }
}

async function saveAll() {
  if (!viewer.value?.editable) return
  saving.value = true
  try {
    const data = await saveSystemConfigEffective(currentSnapshot())
    assignFormState(data)
    ElMessage.success('系统配置已保存')
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function exportJsonSnapshot() {
  if (!configPayload.value) {
    await reloadAll()
  }
  const blob = new Blob([JSON.stringify({
    config: configPayload.value,
    patrolSnapshot: patrolSnapshot.value
  }, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `system-config-snapshot-${Date.now()}.json`
  anchor.click()
  URL.revokeObjectURL(url)
}

async function exportPdfReport() {
  if (!configPayload.value) {
    await reloadAll()
  }
  const [{ jsPDF }] = await Promise.all([import('jspdf')])
  const pdf = new jsPDF({ unit: 'pt', format: 'a4' })
  const lines = [
    'ThreshCore 系统配置与效果报告',
    `平台：${platformSummary.value}`,
    `角色：${viewerRoleText.value}`,
    `热生效项：${hotItemCount.value} 项`,
    `需重启项：${restartItemCount.value} 项`,
    `AI Key 状态：${aiKeyStatusText.value}`,
    `最近保存：${savedAt.value ? formatDateTime(savedAt.value) : '未保存'}`,
    `巡检快照：${patrolSnapshot.value?.timestamp || '暂无'}`,
    '',
    ...(messages.value || []).map(msg => `- ${msg}`)
  ]
  let y = 48
  pdf.setFont('helvetica', 'bold')
  pdf.setFontSize(16)
  pdf.text(lines[0], 40, y)
  y += 28
  pdf.setFont('helvetica', 'normal')
  pdf.setFontSize(11)
  for (const line of lines.slice(1)) {
    const wrapped = pdf.splitTextToSize(line, 500)
    pdf.text(wrapped, 40, y)
    y += wrapped.length * 16
    if (y > 760) {
      pdf.addPage()
      y = 48
    }
  }
  pdf.save(`system-config-report-${Date.now()}.pdf`)
}

function removeListItem(list, index) {
  list.splice(index, 1)
}

function normalizeList(list) {
  return (list || []).map(item => String(item).trim()).filter(Boolean)
}

function numberOrDash(value) {
  return value == null || value === '' ? '--' : Number(value).toFixed(1)
}

function formatDateTime(value) {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString()
}

function goToTab(tab) {
  window.dispatchEvent(new CustomEvent('ops-navigate-tab', { detail: { tab } }))
}

function focusSection(sectionKey = 'pathPolicy') {
  const targetMap = {
    runtime: runtimeRef,
    collector: collectorRef,
    ai: aiRef,
    pathPolicy: pathPolicyRef
  }
  const target = targetMap[sectionKey]?.value
  target?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
}

defineExpose({ focusSection })

reloadAll()
</script>

<style scoped>
.config-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}

.bootstrap-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
}

.bootstrap-card {
  padding: 18px;
  border-radius: 18px;
  background: linear-gradient(180deg, #ffffff, #f8fafc);
  border: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.06);
}

.bootstrap-card__head {
  display: flex;
  justify-content: space-between;
  align-items: start;
  gap: 12px;
}

.bootstrap-card__head strong {
  display: block;
  margin-top: 8px;
  font-size: 22px;
  color: #0f172a;
}

.bootstrap-card__meta {
  display: block;
  margin-top: 10px;
  color: #64748b;
  line-height: 1.6;
}

.bootstrap-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.bootstrap-list {
  margin-top: 12px;
}

.bootstrap-list__row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bootstrap-list__row + .bootstrap-list__row {
  margin-top: 10px;
}

.bootstrap-list__label {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}

.bootstrap-empty {
  font-size: 13px;
  color: #64748b;
}

.summary-card,
.effect-panel,
.detail-box {
  padding: 18px;
  border-radius: 18px;
  background: linear-gradient(180deg, #ffffff, #f8fafc);
  border: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.06);
}

.summary-card strong,
.effect-panel strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
  color: #0f172a;
}

.summary-card small,
.effect-panel p {
  display: block;
  margin-top: 8px;
  line-height: 1.55;
  color: #475569;
}

.summary-label,
.panel-label {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #0f766e;
}

.status-banner,
.message-stack {
  display: grid;
  gap: 10px;
}

.config-layout {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.config-card {
  padding: 22px;
  border-radius: 22px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 250, 252, 0.96));
  border: 1px solid rgba(148, 163, 184, 0.18);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.06);
}

.config-card--compact {
  padding: 12px 14px;
}

.path-policy-panel {
  padding-bottom: 10px;
}

.path-policy-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.path-policy-bar h2 {
  margin: 0;
  font-size: 16px;
  color: #0f172a;
}

.path-policy-meta {
  flex: 1;
  min-width: 120px;
  font-size: 12px;
  color: #64748b;
}

.path-policy-table {
  width: 100%;
}

.path-policy-table :deep(.el-table__cell) {
  padding: 4px 0;
}

.path-policy-table :deep(.el-table__header .el-table__cell) {
  padding: 6px 0;
  font-size: 12px;
}

.path-policy-input :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
  padding: 0 4px;
}

.path-cell {
  font-size: 12px;
  color: #334155;
  font-family: var(--ops-font-mono, ui-monospace, monospace);
}

.denied-inline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed rgba(148, 163, 184, 0.35);
}

.denied-inline-label {
  font-size: 11px;
  font-weight: 600;
  color: #b91c1c;
}

.runtime-panel {
  padding-bottom: 10px;
}

.runtime-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 16px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  background: rgba(248, 250, 252, 0.9);
  border: 1px solid rgba(148, 163, 184, 0.2);
}

.runtime-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #0f172a;
  white-space: nowrap;
}

.runtime-field {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1 1 180px;
}

.runtime-field--mode {
  flex: 0 1 200px;
}

.runtime-field-label {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
}

.runtime-field :deep(.el-input),
.runtime-field :deep(.el-select) {
  flex: 1;
  min-width: 100px;
}

.runtime-dual {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}

.runtime-mini-block {
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(255, 255, 255, 0.7);
  min-width: 0;
}

.runtime-mini-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
}

.runtime-mini-count {
  font-size: 11px;
  font-weight: 400;
  color: #64748b;
  margin-right: auto;
}

.runtime-chip-line {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.runtime-chip-edit {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex: 1 1 140px;
  max-width: 100%;
  min-width: 120px;
}

.runtime-chip-edit--port {
  flex: 0 1 120px;
  min-width: 100px;
}

.runtime-chip-edit :deep(.el-input),
.runtime-chip-edit :deep(.el-input-number) {
  flex: 1;
  min-width: 0;
}

.runtime-metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px 12px;
}

.runtime-metric-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.95);
  border: 1px solid rgba(148, 163, 184, 0.16);
  min-width: 0;
}

.runtime-metric-label {
  flex: 1;
  font-size: 11px;
  color: #64748b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.runtime-metric-cell :deep(.el-input-number) {
  width: 88px;
  flex-shrink: 0;
}

.card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.card-head h2 {
  margin: 0;
  font-size: 22px;
  color: #0f172a;
}

.card-head p {
  margin: 8px 0 0;
  color: #475569;
  line-height: 1.6;
}

.config-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.split-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.triple-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.switch-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
  margin-bottom: 10px;
}

.switch-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(15, 118, 110, 0.08), rgba(14, 165, 233, 0.06));
  border: 1px solid rgba(15, 118, 110, 0.12);
  color: #0f172a;
  font-weight: 600;
}

.list-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.list-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.list-row--compact :deep(.el-input-number) {
  width: 180px;
}

.tag-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.secret-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.secret-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.secret-tip {
  color: #b45309;
  font-size: 12px;
}

.effect-card {
  background:
    radial-gradient(circle at top right, rgba(34, 197, 94, 0.08), transparent 28%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 250, 252, 0.96));
}

.effect-grid,
.effect-detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}

.effect-grid--single {
  grid-template-columns: minmax(240px, 420px);
}

.effect-links-hint,
.effect-links-sub {
  margin: 0 0 10px;
  font-size: 13px;
  color: #64748b;
  line-height: 1.55;
}

.effect-link-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin-bottom: 8px;
}

.effect-detail-grid {
  margin-top: 14px;
}

.detail-box h3 {
  margin: 0 0 12px;
  font-size: 16px;
  color: #0f172a;
}

.detail-box ul {
  margin: 0;
  padding-left: 18px;
  color: #475569;
  line-height: 1.7;
}

.save-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  border-radius: 18px;
  background: rgba(15, 23, 42, 0.94);
  color: #f8fafc;
  box-shadow: 0 -8px 28px rgba(15, 23, 42, 0.16);
  backdrop-filter: blur(14px);
}

.save-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.save-copy span {
  color: rgba(241, 245, 249, 0.74);
  font-size: 13px;
}

.save-actions {
  display: flex;
  gap: 10px;
}

@media (max-width: 960px) {
  .save-bar,
  .card-head {
    flex-direction: column;
  }

  .split-grid,
  .triple-grid,
  .runtime-dual {
    grid-template-columns: 1fr;
  }

  .runtime-metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .runtime-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .runtime-field {
    flex: 1 1 100%;
  }

  .save-actions {
    width: 100%;
    justify-content: stretch;
  }

  .save-actions .el-button {
    flex: 1;
  }
}
</style>
