<template>
  <div class="assistant-message" :class="{ 'assistant-message--streaming': streaming }">
    <div class="assistant-message__aside" aria-hidden="true">
      <div class="assistant-message__avatar">
        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path
            d="M12 2a2 2 0 0 1 2 2c0 .74-.4 1.39-1 1.73V7h1a7 7 0 0 1 7 7v1h1a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-1v1a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-1H2a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1h1v-1a7 7 0 0 1 7-7h1V5.73c-.6-.34-1-.99-1-1.73a2 2 0 0 1 2-2z"
            fill="currentColor"
            opacity="0.95"
          />
          <circle cx="9" cy="13" r="1.2" fill="#fff" />
          <circle cx="15" cy="13" r="1.2" fill="#fff" />
        </svg>
      </div>
      <span class="assistant-message__name">ThreshCore</span>
    </div>
    <div class="assistant-message__body">
      <div
        ref="rootRef"
        class="assistant-prose"
        :class="{
          'assistant-prose--streaming': streaming,
          'assistant-prose--settled': !streaming && content
        }"
        v-html="html"
      />
      <div v-if="!streaming && content" class="assistant-message__toolbar">
        <button
          type="button"
          class="assistant-toolbar-btn"
          :title="copied ? '已复制' : '复制回复'"
          @click="copyMessage"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <rect x="9" y="9" width="11" height="11" rx="2" stroke="currentColor" stroke-width="1.6" />
            <path d="M7 15H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="1.6" />
          </svg>
          <span>{{ copied ? '已复制' : '复制' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { renderAssistantMarkdown, bindProseCopyButtons } from '../../utils/assistantMarkdown'

const props = defineProps({
  content: { type: String, default: '' },
  streaming: { type: Boolean, default: false }
})

const rootRef = ref(null)
const renderTick = ref(0)
const copied = ref(false)
let debounceTimer = null
let copiedTimer = null

watch(
  () => props.content,
  () => {
    if (!props.streaming) {
      renderTick.value += 1
      return
    }
    if (debounceTimer != null) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      debounceTimer = null
      renderTick.value += 1
    }, 32)
  },
  { immediate: true }
)

onUnmounted(() => {
  if (debounceTimer != null) clearTimeout(debounceTimer)
  if (copiedTimer != null) clearTimeout(copiedTimer)
})

const html = computed(() => {
  renderTick.value
  return renderAssistantMarkdown(props.content, { streaming: props.streaming })
})

function bindCopy() {
  nextTick(() => bindProseCopyButtons(rootRef.value))
}

async function copyMessage() {
  const text = props.content?.trim()
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    if (copiedTimer != null) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => { copied.value = false }, 1600)
  } catch {
    copied.value = false
  }
}

watch(html, bindCopy)
onMounted(bindCopy)
</script>

<style scoped>
.assistant-message__aside {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  width: 40px;
}

.assistant-message__name {
  font-size: 10px;
  font-weight: 600;
  color: #8e8ea0;
  letter-spacing: 0.02em;
  line-height: 1;
  text-align: center;
  max-width: 48px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-message__toolbar {
  display: flex;
  gap: 4px;
  margin-top: 8px;
  opacity: 0;
  transform: translateY(2px);
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.assistant-message:hover .assistant-message__toolbar,
.assistant-message:focus-within .assistant-message__toolbar {
  opacity: 1;
  transform: translateY(0);
}

.assistant-toolbar-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  color: #6b7280;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.assistant-toolbar-btn svg {
  width: 14px;
  height: 14px;
}

.assistant-toolbar-btn:hover {
  background: #f9fafb;
  color: #374151;
  border-color: #d1d5db;
}
</style>
