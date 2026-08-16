<template>
  <div class="ops-page environment-status">
    <OpsPageHeader
      title="环境状态"
      subtitle="连接、AI、向量库与本机执行能力一览；异常项请按提示修复"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="refresh">刷新</el-button>
        <el-button text @click="openPathPolicy">路径白名单</el-button>
      </template>
    </OpsPageHeader>

    <el-alert
      v-if="platform?.runtime?.dbReachable === false"
      type="warning"
      :closable="false"
      show-icon
      class="env-alert"
      title="数据库未连接"
      description="日志分析、任务记录与审计依赖 MySQL。请检查 DB 服务与 application-dev.yml 中的连接配置。"
    />

    <el-row :gutter="16">
      <el-col v-for="card in summaryCards" :key="card.key" :xs="24" :sm="12" :md="8">
        <el-card shadow="never" class="status-card" :class="card.level">
          <div class="card-label">{{ card.label }}</div>
          <div class="card-value">{{ card.value }}</div>
          <div class="card-desc">{{ card.desc }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="detail-card">
      <template #header><span>运行环境</span></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="操作系统">{{ platformText }}</el-descriptions-item>
        <el-descriptions-item label="架构">{{ archText }}</el-descriptions-item>
        <el-descriptions-item label="Profile">{{ profilesText }}</el-descriptions-item>
        <el-descriptions-item label="数据源">{{ platform?.runtime?.dataSource || 'live' }}</el-descriptions-item>
        <el-descriptions-item label="日志采集">
          {{ logCollectorText }}
        </el-descriptions-item>
        <el-descriptions-item label="全局演练模式">{{ dryRunText }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="never" class="detail-card">
      <template #header><span>服务依赖</span></template>
      <el-table :data="dependencyRows" size="small" stripe>
        <el-table-column prop="name" label="组件" width="140" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.ok ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card shadow="never" class="detail-card">
      <template #header><span>安全与执行策略</span></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="AI 已配置">{{ boolLabel(platform?.security?.aiConfigured) }}</el-descriptions-item>
        <el-descriptions-item label="最小权限执行">{{ boolLabel(platform?.security?.minPrivilegeEnabled) }}</el-descriptions-item>
        <el-descriptions-item label="运行用户">{{ platform?.security?.runAsUser || '当前进程' }}</el-descriptions-item>
        <el-descriptions-item label="自动修复">{{ boolLabel(platform?.security?.autoRemediationEnabled === 'true') }}</el-descriptions-item>
        <el-descriptions-item label="自主运维">{{ boolLabel(platform?.security?.autonomousOpsEnabled !== 'false') }}</el-descriptions-item>
        <el-descriptions-item label="在线工具">{{ mcpOnlineCount }} / {{ mcpTotal }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { getPlatformInfo, getKnowledgeStatus, getMcpTools } from '../api'
import { mergeToolRegistry } from '../utils/mcpToolsMeta'
import OpsPageHeader from './OpsPageHeader.vue'

const loading = ref(false)
const platform = ref(null)
const knowledge = ref(null)
const mcpTools = ref([])
const mcpLoadError = ref(false)

const emit = defineEmits(['open-path-policy'])

const mcpRegistryLoaded = ref(false)

const mcpTotal = computed(() => mcpTools.value.length)
const mcpOnlineCount = computed(() => mcpTools.value.filter((t) => t.status === 'online').length)

const archText = computed(() => {
  const p = platform.value?.platform
  if (!p) return '—'
  return p.osArch || p.arch || '—'
})

const platformText = computed(() => {
  const p = platform.value?.platform
  if (!p) return '—'
  return [p.osName, p.osVersion].filter(Boolean).join(' ')
})

const profilesText = computed(() => {
  const arr = platform.value?.activeProfiles
  return Array.isArray(arr) && arr.length ? arr.join(', ') : 'default'
})

const dryRunText = computed(() =>
  platform.value?.security?.globalDryRun ? '是（仅预览）' : '否（可真实写）'
)

const logCollectorText = computed(() => {
  const r = platform.value?.runtime
  if (!r) return '—'
  if (r.logCollectorMode === 'auto') {
    const n = Array.isArray(r.logCollectorPaths) ? r.logCollectorPaths.length : 0
    return `自动感知（${n} 个根路径）`
  }
  return r.logCollectorPath || '未配置'
})

const summaryCards = computed(() => {
  const aiOk = platform.value?.security?.aiConfigured
  const qdrantOk = knowledge.value?.qdrantConnected
  const kbReady = knowledge.value?.ready
  const docCount = knowledge.value?.documentCount ?? 0
  const mcpOk = !mcpLoadError.value && mcpRegistryLoaded.value && mcpOnlineCount.value > 0
  return [
    {
      key: 'ai',
      label: 'AI 助手',
      value: aiOk ? '就绪' : '未配置',
      desc: aiOk ? '对话与语义检索可用' : '请配置 AI_API_KEY',
      level: aiOk ? 'ok' : 'warn'
    },
    {
      key: 'kb',
      label: '知识库',
      value: kbReady
        ? (docCount > 0 ? '就绪' : '已连接（空库）')
        : qdrantOk
          ? '部分就绪'
          : '未连接',
      desc: knowledge.value?.probeHint
        || (docCount > 0 ? `${docCount} 篇文档` : knowledge.value?.embeddingMode === 'spring-ai' ? '语义向量' : '降级向量或待配置'),
      level: kbReady ? 'ok' : qdrantOk ? 'warn' : 'warn'
    },
    {
      key: 'mcp',
      label: '本机工具',
      value: mcpLoadError.value ? '连接失败' : mcpOk ? '已连接' : !mcpRegistryLoaded.value ? '未连接' : mcpOnlineCount.value === 0 ? '无在线工具' : '部分离线',
      desc: mcpLoadError.value ? '请检查登录或后端服务' : `${mcpOnlineCount.value}/${mcpTotal.value} 在线`,
      level: mcpOk ? 'ok' : 'warn'
    }
  ]
})

const dependencyRows = computed(() => [
  {
    name: '后端 API',
    ok: !!platform.value,
    status: platform.value ? '正常' : '未知',
    detail: platform.value ? '平台信息可读' : '无法获取 /api/platform/info'
  },
  {
    name: 'MySQL',
    ok: platform.value?.runtime?.dbReachable !== false,
    status: platform.value?.runtime?.dbReachable ? '已连接' : '未连接',
    detail: platform.value?.runtime?.dbReachable ? '数据源探测通过' : '请检查 DB_PASSWORD 与 MySQL 服务'
  },
  {
    name: 'Qdrant',
    ok: knowledge.value?.qdrantConnected,
    status: knowledge.value?.qdrantConnected
      ? (knowledge.value?.collectionExists ? '已连接' : '可达·空库')
      : '未连接',
    detail: knowledge.value?.probeHint || knowledge.value?.qdrantUrl || 'http://localhost:6333'
  },
  {
    name: 'Embedding',
    ok: knowledge.value?.embeddingModel === 'available',
    status: knowledge.value?.embeddingModel === 'available' ? '可用' : '降级',
    detail: `模式：${knowledge.value?.embeddingMode || '—'}`
  },
  {
    name: '运维工具箱',
    ok: !mcpLoadError.value && mcpRegistryLoaded.value && mcpOnlineCount.value > 0,
    status: mcpLoadError.value ? '连接失败' : !mcpRegistryLoaded.value ? '未连接' : mcpOnlineCount.value > 0 ? '在线' : '无在线工具',
    detail: mcpLoadError.value ? '无法访问 /api/mcp/tools' : `${mcpOnlineCount.value}/${mcpTotal.value} 工具可用`
  }
])

function boolLabel (v) {
  return v ? '是' : '否'
}

async function refresh () {
  loading.value = true
  mcpLoadError.value = false
  mcpRegistryLoaded.value = false
  try {
    let mcpRaw = null
    const [p, k, toolsResp] = await Promise.all([
      getPlatformInfo().catch(() => null),
      getKnowledgeStatus().catch(() => null),
      getMcpTools().catch(() => {
        mcpLoadError.value = true
        return null
      })
    ])
    platform.value = p
    knowledge.value = k
    mcpRaw = toolsResp
    if (mcpRaw != null) {
      mcpRegistryLoaded.value = true
      mcpTools.value = mergeToolRegistry(mcpRaw, { registryLoaded: true })
    } else {
      mcpTools.value = mergeToolRegistry(null, { registryLoaded: false })
    }
  } finally {
    loading.value = false
  }
}

function openPathPolicy () {
  emit('open-path-policy')
}

onMounted(refresh)

defineExpose({ refresh })
</script>

<style scoped>
.environment-status .page-header {
  margin-bottom: 12px;
}

.environment-status .page-header h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.environment-status .subtitle {
  margin: 0;
  color: var(--ops-text-muted);
  font-size: 13px;
}

.environment-status .env-alert {
  margin-bottom: 14px;
}

.toolbar {
  margin-bottom: 16px;
  display: flex;
  gap: 8px;
}

.status-card {
  margin-bottom: 12px;
}

.status-card.ok .card-value {
  color: var(--el-color-success);
}

.status-card.warn .card-value {
  color: var(--el-color-warning);
}

.card-label {
  font-size: 12px;
  color: var(--ops-text-muted);
}

.card-value {
  font-size: 22px;
  font-weight: 600;
  margin: 6px 0;
}

.card-desc {
  font-size: 12px;
  color: var(--ops-text-muted);
}

.detail-card {
  margin-bottom: 16px;
}
</style>
