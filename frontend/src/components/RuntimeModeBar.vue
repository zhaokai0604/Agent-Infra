<template>
  <div class="runtime-mode-bar" :class="{ 'runtime-mode-bar--compact': compact }">
    <el-tooltip
      v-for="chip in modes"
      :key="chip.key"
      :content="chip.tip || chip.label"
      placement="bottom"
      :show-after="280"
    >
      <el-tag
        size="small"
        :type="chip.type"
        effect="plain"
        class="mode-chip"
      >
        {{ chip.label }}
      </el-tag>
    </el-tooltip>
    <el-button
      v-if="showRefresh"
      text
      circle
      size="small"
      :loading="loading"
      title="刷新运行状态"
      @click="refresh(true)"
    >
      <el-icon><Refresh /></el-icon>
    </el-button>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { usePlatformHealth } from '../composables/usePlatformHealth'

defineProps({
  compact: { type: Boolean, default: false },
  showRefresh: { type: Boolean, default: false }
})

const { runtimeMode: modes, loading, refresh } = usePlatformHealth()

onMounted(() => refresh())
</script>

<style scoped>
.runtime-mode-bar {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.runtime-mode-bar--compact {
  overflow-x: auto;
  scrollbar-width: none;
  max-width: min(360px, 42vw);
}

.runtime-mode-bar--compact::-webkit-scrollbar {
  display: none;
}

.runtime-mode-bar--compact .mode-chip {
  font-size: 11px;
  padding: 0 7px;
  height: 22px;
  flex-shrink: 0;
}

.mode-chip {
  border-radius: 999px;
  flex-shrink: 0;
}
</style>
