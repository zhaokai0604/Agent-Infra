<template>
  <div class="agent-welcome">
    <div class="agent-welcome__hero">
      <h2>先给我一个任务</h2>
      <p>
        我会先梳理目标、约束和成功标准，再进入观测、规划、执行、验证和复盘。
        涉及写操作时，默认先预览，确认后落地。
      </p>
    </div>

    <div class="agent-welcome__mode-wrap">
      <RuntimeModeBar compact class="welcome-mode-bar" />
    </div>

    <div class="agent-welcome__chips">
      <button
        v-for="(item, idx) in chips"
        :key="idx"
        type="button"
        class="welcome-chip"
        @click="$emit('select', item.cmd)"
      >
        <el-icon class="chip-icon"><component :is="item.icon" /></el-icon>
        <span>{{ item.label }}</span>
      </button>

      <button
        v-if="patrolPending"
        type="button"
        class="welcome-chip welcome-chip--alert"
        @click="$emit('patrol')"
      >
        <el-icon class="chip-icon"><component :is="patrolItem.icon" /></el-icon>
        <span>{{ patrolItem.label }}</span>
      </button>
    </div>

    <button type="button" class="skills-link" @click="$emit('open-skills')">
      查看全部技能 →
    </button>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  Coin,
  DataAnalysis,
  Delete,
  Document,
  Link,
  TrendCharts
} from '@element-plus/icons-vue'
import { getPlatformInfo } from '../../api'
import { buildWelcomeQuickChips } from '../../utils/platformQuickPaths'
import { PATROL_QUICK_ITEM } from '../../constants/workbenchQuickActions'
import RuntimeModeBar from '../RuntimeModeBar.vue'

const ICON_MAP = {
  DataAnalysis,
  Coin,
  TrendCharts,
  Document,
  Delete,
  Link
}

defineProps({
  patrolPending: { type: Boolean, default: false }
})

defineEmits(['select', 'patrol', 'open-skills'])

const platform = ref(null)

const chips = computed(() =>
  buildWelcomeQuickChips(platform.value).map(item => ({
    ...item,
    icon: ICON_MAP[item.icon] || DataAnalysis
  }))
)

const patrolItem = PATROL_QUICK_ITEM

onMounted(async () => {
  platform.value = await getPlatformInfo().catch(() => null)
})
</script>

<style scoped>
.agent-welcome {
  padding: 48px 0 32px;
  text-align: center;
}

.agent-welcome__hero h2 {
  margin: 0 0 10px;
  font-size: 28px;
  font-weight: 600;
  color: var(--ops-text);
  letter-spacing: 0;
}

.agent-welcome__hero p {
  margin: 0 auto;
  max-width: 560px;
  font-size: 14px;
  line-height: 1.65;
  color: var(--agent-muted);
}

.agent-welcome__mode-wrap {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

.welcome-mode-bar {
  justify-content: center;
}

.agent-welcome__chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 28px;
}

.welcome-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border: 1px solid var(--agent-border);
  border-radius: 999px;
  background: var(--agent-chip-bg);
  color: var(--ops-text);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, transform 0.15s ease;
}

.welcome-chip:hover {
  background: var(--agent-chip-hover);
  border-color: rgba(13, 148, 136, 0.35);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
}

.welcome-chip--alert {
  border-color: rgba(245, 158, 11, 0.35);
  background: rgba(254, 243, 199, 0.5);
  color: #b45309;
}

.chip-icon {
  font-size: 16px;
  color: var(--ops-primary);
}

.skills-link {
  margin-top: 20px;
  border: none;
  background: none;
  color: var(--ops-primary);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  padding: 4px 8px;
}

.skills-link:hover {
  text-decoration: underline;
}
</style>
