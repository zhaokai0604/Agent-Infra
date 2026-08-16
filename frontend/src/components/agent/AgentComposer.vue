<template>
  <div class="agent-composer">
    <div v-if="pendingAttachment" class="attachment-chip">
      <span>已附加：{{ pendingAttachment.name }}</span>
      <el-button link type="danger" size="small" @click="$emit('clear-attachment')">移除</el-button>
    </div>

    <div class="composer-shell">
      <input
        ref="fileInputRef"
        type="file"
        class="hidden-file-input"
        accept=".txt,.log,.csv,.json,.md,text/plain"
        @change="onFileChange"
      />

      <el-input
        :model-value="modelValue"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 8 }"
        placeholder="写下任务目标、约束和成功标准，例如：检查磁盘、分析日志、生成处置计划。"
        class="composer-input"
        @update:model-value="$emit('update:modelValue', $event)"
        @keydown.enter.exact.prevent="$emit('send')"
        @keydown.shift.enter="$emit('newline')"
      />

      <div class="composer-bar">
        <div class="composer-bar__left">
          <el-button class="tool-btn" :icon="FolderOpened" text size="small" title="附加文本文件" @click="openFilePicker">
            证据
          </el-button>
          <el-button
            class="tool-btn"
            :icon="Microphone"
            text
            size="small"
            :type="listeningVoice ? 'danger' : 'default'"
            title="语音输入"
            @click="$emit('toggle-voice')"
          >
            语音
          </el-button>
          <span class="composer-kbd">Enter 发送 · Shift+Enter 换行</span>
        </div>

        <div class="composer-bar__right">
          <span v-if="routeHint.label" class="route-hint" :class="`route-hint--${routeHint.mode}`">
            {{ routeHint.label }}
          </span>
          <el-button
            v-if="stoppable"
            type="danger"
            class="send-btn"
            round
            :icon="VideoPause"
            title="停止生成"
            @click="$emit('stop')"
          >
            停止
          </el-button>
          <el-button
            v-else
            type="primary"
            class="send-btn"
            round
            :loading="loading"
            :icon="Promotion"
            title="发送任务"
            @click="$emit('send')"
          >
            发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { FolderOpened, Microphone, Promotion, VideoPause } from '@element-plus/icons-vue'

defineProps({
  modelValue: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  stoppable: { type: Boolean, default: false },
  listeningVoice: { type: Boolean, default: false },
  pendingAttachment: { type: Object, default: null },
  routeHint: {
    type: Object,
    default: () => ({ mode: 'idle', label: '' })
  }
})

const emit = defineEmits([
  'update:modelValue',
  'send',
  'stop',
  'newline',
  'attach',
  'toggle-voice',
  'clear-attachment'
])

const fileInputRef = ref(null)

function openFilePicker() {
  fileInputRef.value?.click()
}

function onFileChange(event) {
  const file = event.target.files?.[0]
  if (file) emit('attach', file)
  event.target.value = ''
}

defineExpose({ openFilePicker })
</script>

<style scoped>
.agent-composer {
  padding: 8px 0 16px;
  background: transparent;
}

.composer-shell {
  border: 1px solid var(--agent-border);
  border-radius: 16px;
  background: var(--agent-surface);
  box-shadow: 0 2px 16px rgba(15, 23, 42, 0.05);
  overflow: hidden;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.composer-shell:focus-within {
  border-color: rgba(13, 148, 136, 0.5);
  box-shadow: 0 4px 24px rgba(13, 148, 136, 0.08);
}

.composer-input :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none !important;
  padding: 14px 16px 8px;
  font-size: 15px;
  line-height: 1.55;
  background: transparent;
  resize: none;
  min-height: 52px !important;
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px 12px;
  border-top: 1px solid var(--agent-border);
  background: var(--ops-panel-soft, #f8fafc);
}

.composer-bar__left,
.composer-bar__right {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.composer-bar__left {
  flex: 1;
  flex-wrap: wrap;
}

.tool-btn {
  padding: 4px 8px;
  font-size: 13px;
}

.composer-kbd {
  font-size: 11px;
  color: var(--agent-muted);
  margin-left: 4px;
}

.route-hint {
  font-size: 11px;
  color: var(--agent-muted);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.route-hint--agent-auto {
  color: #0f766e;
}

.route-hint--idle,
.route-hint--nl {
  color: var(--agent-muted);
}

.route-hint--autonomous {
  color: #7c3aed;
}

.send-btn {
  flex-shrink: 0;
  padding: 8px 18px;
  font-weight: 600;
}

.attachment-chip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  padding: 8px 12px;
  background: #f4f4f5;
  border-radius: 10px;
  font-size: 13px;
  color: #606266;
}

.hidden-file-input {
  display: none;
}

@media (max-width: 640px) {
  .composer-kbd {
    display: none;
  }

  .route-hint {
    display: none;
  }
}
</style>
