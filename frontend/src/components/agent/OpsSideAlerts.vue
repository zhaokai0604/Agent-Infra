<template>
  <section v-if="systemPressureAlert || patrolPending?.hasPending" class="ops-side-alerts">
    <article v-if="systemPressureAlert" class="side-alert" :class="`side-alert--${systemPressureAlert.level}`">
      <div class="side-alert__main">
        <strong>{{ systemPressureAlert.title }}</strong>
        <span>{{ systemPressureAlert.detail }}</span>
      </div>
      <el-button size="small" text type="warning" @click="prefill(systemPressureAlert.command)">
        带入 Agent
      </el-button>
    </article>

    <article v-if="patrolPending?.hasPending" class="side-alert side-alert--patrol">
      <div class="side-alert__main">
        <strong>巡检待处理</strong>
        <span>{{ patrolPending.summary || '有待确认修复步骤' }}</span>
      </div>
      <el-button size="small" text type="primary" @click="prefill('继续处理巡检待办，确认执行')">
        带入 Agent
      </el-button>
    </article>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getPatrolRemediationPending, getPerformanceData } from '../../api'
import { dispatchOpsAgentPrefill } from '../../utils/opsAgentNavigate'

const performanceSnapshot = ref({ cpuUsage: null, memoryUsage: null })
const patrolPending = ref({ hasPending: false })
let pollTimer = null

const systemPressureAlert = computed(() => {
  const cpu = Number(performanceSnapshot.value.cpuUsage)
  const mem = Number(performanceSnapshot.value.memoryUsage)
  const cpuHigh = Number.isFinite(cpu) && cpu >= 85
  const memHigh = Number.isFinite(mem) && mem >= 80
  if (!cpuHigh && !memHigh) return null
  const bits = []
  if (cpuHigh) bits.push(`CPU ${cpu.toFixed(1)}%`)
  if (memHigh) bits.push(`内存 ${mem.toFixed(1)}%`)
  const critical = cpu >= 92 || mem >= 90
  return {
    level: critical ? 'critical' : 'warn',
    title: critical ? '本机资源高压' : '本机资源偏高',
    detail: `${bits.join('，')}，可带入 Agent 排查高占用进程。`,
    command: 'CPU或内存占用过高，帮我查一下高占用进程'
  }
})

async function refreshPerformanceSnapshot () {
  try {
    const data = await getPerformanceData({ silent: true })
    performanceSnapshot.value = {
      cpuUsage: data?.cpuUsage ?? null,
      memoryUsage: data?.memoryUsage ?? null
    }
  } catch {
    /* keep previous snapshot */
  }
}

async function refreshPatrolPending () {
  try {
    const data = await getPatrolRemediationPending({ silent: true })
    patrolPending.value = data && typeof data === 'object' ? data : { hasPending: false }
  } catch {
    patrolPending.value = { hasPending: false }
  }
}

function prefill (message) {
  dispatchOpsAgentPrefill(message)
}

function handlePatrolPendingEvent (event) {
  patrolPending.value = event?.detail && typeof event.detail === 'object'
    ? event.detail
    : { hasPending: false }
}

onMounted(() => {
  refreshPerformanceSnapshot()
  refreshPatrolPending()
  pollTimer = setInterval(() => {
    refreshPerformanceSnapshot()
    refreshPatrolPending()
  }, 120000)
  window.addEventListener('ops-patrol-pending-change', handlePatrolPendingEvent)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
  window.removeEventListener('ops-patrol-pending-change', handlePatrolPendingEvent)
})
</script>

<style scoped>
.ops-side-alerts {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 0;
}

.side-alert {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 8px;
  border: 1px solid rgba(251, 191, 36, 0.3);
  background: rgba(255, 251, 235, 0.72);
  color: #92400e;
}

.side-alert--critical {
  border-color: rgba(248, 113, 113, 0.32);
  background: rgba(254, 242, 242, 0.72);
  color: #991b1b;
}

.side-alert--patrol {
  border-color: rgba(45, 212, 191, 0.28);
  background: rgba(240, 253, 250, 0.72);
  color: #0f766e;
}

.side-alert__main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.side-alert__main strong {
  font-size: 12px;
  line-height: 1.35;
}

.side-alert__main span {
  color: #64748b;
  font-size: 11px;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.side-alert :deep(.el-button) {
  flex-shrink: 0;
  height: 24px;
  padding: 0 2px;
  font-size: 11px;
}
</style>
