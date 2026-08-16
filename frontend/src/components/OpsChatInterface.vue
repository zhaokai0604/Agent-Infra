<template>
  <div class="ops-page ops-chat-container ops-chat-container--agent">
    <div class="chat-messages" ref="messagesContainer" @scroll="onMessagesScroll">
      <div v-if="messages.length === 0" class="agent-chat-column">
        <AgentWelcome
          :patrol-pending="patrolPending?.hasPending"
          @select="sendQuickCommand"
          @patrol="continuePatrolPending"
          @open-skills="openSkills"
        />
      </div>

      <div class="agent-chat-column messages-inner" v-if="messages.length > 0">
        <section v-for="(turn, ti) in chatTurns" :key="ti" class="chat-turn">
          <div v-if="turn.user" class="chat-turn__user">
            <div class="chat-turn__user-bubble">{{ turn.user.content }}</div>
          </div>

          <div
            v-for="{ msg, index } in turn.assistants"
            :key="index"
            class="chat-turn__assistant"
          >
            <div v-if="msg.isToolCalling" class="tool-calling">
              <div class="tool-calling-header">
                <el-icon class="is-loading"><Loading /></el-icon>
                <span>正在执行 {{ msg.toolName ? mcpToolDisplayName(msg.toolName) : '工具' }}…</span>
              </div>
              <div class="tool-command" v-if="msg.toolCommand">
                <code>{{ msg.toolCommand }}</code>
              </div>
            </div>

            <template v-else>
              <div
                v-if="loading && streaming && index === messages.length - 1"
                class="agent-think-panel"
                :class="{ 'is-live': loading && streaming && index === messages.length - 1 }"
              >
                <div class="agent-think-panel__title">
                  <el-icon v-if="loading && streaming && index === messages.length - 1" class="is-loading"><Loading /></el-icon>
                  <span>{{ loading && streaming && index === messages.length - 1 ? '处理中' : '处理过程' }}</span>
                </div>
                <p class="agent-think-panel__hint">{{ streamStatusLabel }}</p>
              </div>
              <AssistantProse
                v-if="msg.content"
                :content="msg.content"
                :streaming="loading && streaming && index === messages.length - 1"
              />
              <RemediationPlanCard
                v-if="msg.remediationPlan && msg.planReady !== false && !msg.writeConfirmed"
                :plan="msg.remediationPlan"
                :loading="loading && confirmPlanIndex === index"
                :observe-only="msg.awaitingConfirm && msg.writeToolsMounted === false"
                :pending-write-tools="msg.pendingWriteTools || []"
                @confirm="confirmPlanFromMessage(index)"
              />
              <AssistantEvidenceBar
                :rag-hits="msg.ragHits"
                :tools-used="msg.toolsUsed || (msg.toolName ? [msg.toolName] : [])"
                :reply-mode="msg.meta?.replyMode || msg.streamEvent?.replyMode"
                :security-outcome="msg.meta?.securityOutcome || msg.streamEvent?.securityOutcome"
                :awm-workflow-id="msg.meta?.awmWorkflowId || msg.streamEvent?.awmWorkflowId"
                :awm-workflow-title="msg.meta?.awmWorkflowTitle || msg.streamEvent?.awmWorkflowTitle"
                :memory-applied="msg.meta?.executionState?.memoryApplied ?? msg.streamEvent?.executionState?.memoryApplied ?? null"
                :feedback-recorded="msg.meta?.executionState?.feedbackRecorded ?? msg.streamEvent?.executionState?.feedbackRecorded ?? null"
                @open-knowledge="openKnowledge"
              />
              <div v-if="contextUsage(msg)" class="assistant-context-usage">
                <span class="assistant-context-usage__label">上下文</span>
                <span>{{ formatContextUsage(contextUsage(msg)) }}</span>
                <span class="assistant-context-usage__model">{{ contextUsage(msg).model || 'default' }}</span>
              </div>
            </template>
          </div>
        </section>

        <div v-if="loading && streaming" class="chat-turn agent-stream-status">
          <div class="agent-stream-status__inner">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>{{ streamStatusLabel }}</span>
          </div>
        </div>
        <div v-else-if="loading" class="chat-turn">
          <div class="prose-typing">
            <span class="prose-typing-dot"></span>
            <span class="prose-typing-dot"></span>
            <span class="prose-typing-dot"></span>
          </div>
        </div>
      </div>
    </div>

    <button
      v-if="showScrollDown"
      type="button"
      class="scroll-down-btn"
      title="回到底部"
      @click="scrollToBottom(true)"
    >
      <el-icon><ArrowDown /></el-icon>
    </button>

    <div class="agent-chat-column composer-sticky">
      <AgentQuickBar
        v-if="messages.length > 0"
        :loading="loading"
        :patrol-pending="!!patrolPending?.hasPending"
        @select="sendQuickCommand"
        @patrol="continuePatrolPending"
      />
      <div class="agent-model-switcher">
        <span class="agent-model-switcher__label">模型</span>
        <el-select
          v-model="selectedModelProfile"
          size="small"
          class="agent-model-switcher__select"
          :disabled="loading"
          aria-label="选择模型"
        >
          <el-option
            v-for="item in modelProfileOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>
      <AgentComposer
        v-model="inputMessage"
        :loading="loading"
        :stoppable="loading"
        :listening-voice="listeningVoice"
        :pending-attachment="pendingAttachment"
        :route-hint="routeHint"
        @send="handleSend"
        @stop="stopGeneration"
        @newline="handleNewLine"
        @attach="onAttachFileFromComposer"
        @toggle-voice="toggleVoiceInput"
        @clear-attachment="clearAttachment"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, ArrowDown } from '@element-plus/icons-vue'
import AgentWelcome from './agent/AgentWelcome.vue'
import AgentComposer from './agent/AgentComposer.vue'
import AgentQuickBar from './agent/AgentQuickBar.vue'
import AssistantProse from './agent/AssistantProse.vue'
import { useComposerRouteHint } from '../composables/useComposerRouteHint'
import { useAgentSession } from '../composables/useAgentSession'
import {
  assistantChatStream,
  captureOpsFailureInsight,
  getPatrolRemediationPending,
  getAssistantModels
} from '../api'
import { generateHumanReadableResponse } from '../utils/mcpHumanReadable'
import { mcpToolDisplayName, extractToolsUsedFromText } from '../utils/mcpToolsMeta'
import { appendSsePayloadFromChunk, flushSseBuffer } from '../utils/sseStream'
import {
  isWorkbenchHighRiskMessage,
  classifyWorkbenchBlockCode,
  workbenchHighRiskBlockMarkdown
} from '../utils/opsIntentSafety'
import {
  userDeclinesTools,
  userConfirmedRemediation
} from '../utils/opsMissionIntent'
import RemediationPlanCard from './RemediationPlanCard.vue'
import AssistantEvidenceBar from './AssistantEvidenceBar.vue'
import {
  parseRemediationPlan,
  hasActionableRemediationPlan,
  hasNoActionableFinding,
  isRemediationToolPlan
} from '../utils/remediationPlan'
import { handleAssistantSsePayload, securityOutcomeLabel } from '../utils/assistantSseMeta'

const { messages, loadSession, clearSession } = useAgentSession()
const inputMessage = ref('')
const routeHint = useComposerRouteHint(inputMessage)
const loading = ref(false)
const streaming = ref(false)
const selectedModelProfile = ref('AUTO')
const modelProfileOptions = ref([
  { value: 'AUTO', label: 'AUTO' },
  { value: 'default', label: '默认模型' }
])
const showScrollDown = ref(false)
let generationToken = 0
let streamAbortController = null
let streamReader = null
const messagesContainer = ref(null)
const pendingAttachment = ref(null)
const patrolPending = ref({ hasPending: false })
const patrolContinueLoading = ref(false)
const listeningVoice = ref(false)
const confirmPlanIndex = ref(-1)
const skipRemediationConfirm = ref(false)
let speechRecognition = null

const chatTurns = computed(() => {
  const turns = []
  let current = null
  for (let i = 0; i < messages.value.length; i++) {
    const msg = messages.value[i]
    if (msg.role === 'user') {
      if (current) turns.push(current)
      current = { user: msg, assistants: [] }
    } else if (current) {
      current.assistants.push({ msg, index: i })
    } else {
      turns.push({ user: null, assistants: [{ msg, index: i }] })
    }
  }
  if (current) turns.push(current)
  return turns
})

const streamStatusLabel = computed(() => {
  const last = messages.value[messages.value.length - 1]
  const mode = last?.meta?.replyMode || last?.streamEvent?.replyMode
  if (mode === 'ORCHESTRATE') return '正在整理任务上下文…'
  if (mode === 'TOOL_AGENT') return '正在调用运维工具…'
  return '正在分析你的问题…'
})
const nowTime = () => new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })

function pushAssistantNotice (content) {
  messages.value.push({
    role: 'assistant',
    content,
    timestamp: nowTime()
  })
  scrollToBottom()
}

function applyPatrolPendingState (data) {
  patrolPending.value = data && typeof data === 'object' ? data : { hasPending: false }
}

const refreshPatrolPending = async () => {
  try {
    const data = await getPatrolRemediationPending({ silent: true })
    applyPatrolPendingState(data)
  } catch {
    patrolPending.value = { hasPending: false }
  }
}

const continuePatrolPending = async () => {
  patrolContinueLoading.value = true
  try {
    await refreshPatrolPending()
    if (!patrolPending.value?.hasPending) {
      pushAssistantNotice('当前没有待执行方案。')
      return
    }
    try {
      await ElMessageBox.confirm(
        '将按巡检待办在白名单与策略范围内执行写操作。是否确认落地？',
        '确认继续处理巡检',
        {
          type: 'warning',
          confirmButtonText: '确认执行',
          cancelButtonText: '取消',
          closeOnClickModal: false
        }
      )
    } catch {
      return
    }
    skipRemediationConfirm.value = true
    try {
      // 必须带巡检续办口令，避免单独「确认执行」被误判为磁盘清理剧本
      await sendQuickCommand('继续处理巡检待办，确认执行')
    } finally {
      skipRemediationConfirm.value = false
    }
  } finally {
    await refreshPatrolPending()
    patrolContinueLoading.value = false
  }
}

function handlePatrolPendingEvent (event) {
  applyPatrolPendingState(event?.detail)
}

let patrolPollTimer = null

onMounted(() => {
  loadSession()
  loadModelProfiles()
  refreshPatrolPending()
  patrolPollTimer = setInterval(refreshPatrolPending, 120000)
  window.addEventListener('ops-patrol-pending-change', handlePatrolPendingEvent)
})

async function loadModelProfiles () {
  try {
    const snapshot = await getAssistantModels()
    const configured = Array.isArray(snapshot?.profiles)
      ? snapshot.profiles.filter(item => item?.enabled && item?.configured)
      : []
    modelProfileOptions.value = [
      { value: 'AUTO', label: 'AUTO' },
      { value: 'default', label: snapshot?.defaultModel ? `默认 · ${snapshot.defaultModel}` : '默认模型' },
      ...configured.map(item => ({
        value: item.name,
        label: `${item.name} · ${item.model || '兼容模型'}`
      }))
    ]
  } catch {
    // Model switching is optional; keep AUTO/default when the status endpoint is unavailable.
  }
}

const clearAttachment = () => {
  pendingAttachment.value = null
}

function readAttachmentFile(f) {
  if (!f) return
  const maxBytes = 48 * 1024
  if (f.size > maxBytes) {
    ElMessage.warning('文本附件请勿超过 48KB')
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    let text = typeof reader.result === 'string' ? reader.result : ''
    if (text.length > 32000) {
      text = text.slice(0, 32000) + '\n...[truncated]'
    }
    pendingAttachment.value = { name: f.name, text }
    ElMessage.success(`已载入：${f.name}`)
  }
  reader.onerror = () => ElMessage.error('读取附件失败')
  reader.readAsText(f, 'UTF-8')
}

const onAttachFileFromComposer = (file) => {
  readAttachmentFile(file)
}

const toggleVoiceInput = () => {
  const SR = typeof window !== 'undefined' && (window.SpeechRecognition || window.webkitSpeechRecognition)
  if (!SR) {
    ElMessage.warning('当前浏览器不支持语音识别，请换用 Chrome 或手动输入')
    return
  }
  if (!speechRecognition) {
    speechRecognition = new SR()
    speechRecognition.lang = 'zh-CN'
    speechRecognition.continuous = false
    speechRecognition.interimResults = false
    speechRecognition.onresult = (ev) => {
      const t = ev.results?.[0]?.[0]?.transcript?.trim()
      if (t) {
        inputMessage.value = (inputMessage.value ? `${inputMessage.value} ${t}` : t).trim()
      }
      listeningVoice.value = false
    }
    speechRecognition.onerror = () => {
      listeningVoice.value = false
      ElMessage.error('语音识别出错，请重试')
    }
    speechRecognition.onend = () => {
      listeningVoice.value = false
    }
  }
  if (listeningVoice.value) {
    try {
      speechRecognition.stop()
    } catch {
      /* noop */
    }
    listeningVoice.value = false
    return
  }
  try {
    listeningVoice.value = true
    speechRecognition.start()
    ElMessage.info('请说话…')
  } catch {
    listeningVoice.value = false
    ElMessage.error('无法启动语音识别')
  }
}

const scrollToBottom = (force = false) => {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
      if (force) showScrollDown.value = false
    }
  })
}

const onMessagesScroll = () => {
  const el = messagesContainer.value
  if (!el) return
  const dist = el.scrollHeight - el.scrollTop - el.clientHeight
  showScrollDown.value = dist > 100
}

const isGenerationCancelled = (token) => token !== generationToken

const stopGeneration = () => {
  generationToken += 1
  streaming.value = false
  loading.value = false
  if (streamAbortController) {
    streamAbortController.abort()
    streamAbortController = null
  }
  if (streamReader) {
    streamReader.cancel().catch(() => {})
    streamReader = null
  }
  const last = messages.value[messages.value.length - 1]
  if (last?.role === 'assistant') {
    if (!String(last.content || '').trim()) {
      messages.value.pop()
    } else if (!String(last.content).includes('（已停止生成）')) {
      last.content = `${last.content}\n\n（已停止生成）`
    }
  }
}

/** Shift+Enter 换行：依赖 textarea 默认行为，仅需占位函数避免模板绑定 undefined 导致 withModifiers 报错 */
const handleNewLine = () => {}

const buildConversationHistory = (priorMessages) => {
  const out = []
  for (const msg of priorMessages) {
    if (msg.isToolCalling) continue
    if (msg.role === 'user') {
      const text = typeof msg.content === 'string' ? msg.content.trim() : ''
      if (text) out.push({ role: 'user', content: text.slice(0, 4000) })
      continue
    }
    if (msg.role === 'assistant') {
      let text = typeof msg.content === 'string' ? msg.content.trim() : ''
      if (!text && msg.toolResult != null && msg.toolName) {
        text = generateHumanReadableResponse(msg.toolName, msg.toolResult) || `[${msg.toolName}] 已执行`
      }
      if (text) out.push({ role: 'assistant', content: text.slice(0, 4000) })
    }
  }
  return out.slice(-24)
}

function buildDraftMessage(rawInput) {
  const base = typeof rawInput === 'string' ? rawInput.trim() : String(rawInput || '').trim()
  if (pendingAttachment.value) {
    return `${base}\n\n> 附件: ${pendingAttachment.value.name}\n${pendingAttachment.value.text}`.trim()
  }
  return base
}

function applySseToAssistant (assistantIdx, payload) {
  handleAssistantSsePayload(payload, {
    onMeta: (meta) => {
      const msg = messages.value[assistantIdx]
      if (!msg) return
      msg.meta = { ...(msg.meta || {}), ...meta }
      if (Array.isArray(meta.ragHits)) msg.ragHits = meta.ragHits
      if (meta.writeConfirmed) msg.writeConfirmed = true
      if (meta.awaitingConfirm != null) msg.awaitingConfirm = !!meta.awaitingConfirm
      if (meta.planPhase) msg.planPhase = meta.planPhase
      if (Array.isArray(meta.plannedTools)) msg.plannedTools = meta.plannedTools
      if (Array.isArray(meta.pendingWriteTools)) msg.pendingWriteTools = meta.pendingWriteTools
      if (Array.isArray(meta.observeTools)) msg.observeTools = meta.observeTools
      if (meta.writeToolsMounted != null) msg.writeToolsMounted = !!meta.writeToolsMounted
      if (meta.securityOutcome === 'EXECUTED' || meta.toolAgentOutcome === 'EXECUTED') {
        msg.writeConfirmed = true
        msg.awaitingConfirm = false
      }
    },
    onEvent: (event) => {
      const msg = messages.value[assistantIdx]
      if (!msg || !event) return
      msg.streamEvent = event
      if (event.contextUsage) {
        msg.contextUsage = event.contextUsage
      }
      if (event.type === 'progress' || (event.phase && event.type !== 'tool-plan')) {
        if (!Array.isArray(msg.thinkSteps)) msg.thinkSteps = []
        const title = event.title || event.message || event.phase
        if (title) {
          const last = msg.thinkSteps[msg.thinkSteps.length - 1]
          if (!last || last.title !== title) {
            msg.thinkSteps.push({ phase: event.phase || 'step', title: String(title) })
          }
        }
      }
      if (event.type === 'tool-plan') {
        // A tool-plan event is only a candidate. Wait for the final result before
        // showing a confirmation card; patrol may find no remediation at all.
        msg.planReady = false
        msg.plannedTools = Array.isArray(event.tools) ? event.tools : []
        msg.planItems = Array.isArray(event.items) ? event.items : []
        msg.planPhase = event.planPhase || msg.planPhase
        if (Array.isArray(event.pendingWriteTools)) msg.pendingWriteTools = event.pendingWriteTools
        if (Array.isArray(event.observeTools)) msg.observeTools = event.observeTools
        if (event.awaitingConfirm != null) msg.awaitingConfirm = !!event.awaitingConfirm
        if (event.writeToolsMounted != null) msg.writeToolsMounted = !!event.writeToolsMounted
        if (!Array.isArray(msg.thinkSteps)) msg.thinkSteps = []
        const remediationPlan = isRemediationToolPlan(event)
        const planTitle = remediationPlan
          ? (event.writeConfirmed ? '计划已确认' : '已生成处置方案')
          : '已生成诊断计划'
        msg.thinkSteps.push({ phase: 'plan', title: planTitle })
        if (!msg.writeConfirmed && remediationPlan) {
          msg.remediationPlan = {
            title: event.planPhase === 'EXECUTE' ? '执行方案' : '处置方案',
            summary: msg.planItems.length
              ? `已生成 ${msg.planItems.length} 步处置方案，详细步骤可在审计链路复核。`
              : '已生成处置方案，详细内容可在审计链路复核。',
            previewOnly: !event.writeConfirmed
          }
        }
      }
      if (event.traceId) msg.traceId = event.traceId
      if (Array.isArray(event.toolsUsed) && event.toolsUsed.length) {
        msg.toolsUsed = event.toolsUsed
      }
      if (event.replyMode || event.securityOutcome || event.awmWorkflowId) {
        msg.meta = {
          ...(msg.meta || {}),
          ...(event.replyMode ? { replyMode: event.replyMode } : {}),
          ...(event.securityOutcome ? { securityOutcome: event.securityOutcome } : {}),
          ...(event.awmWorkflowId ? {
            awmWorkflowId: event.awmWorkflowId,
            awmWorkflowTitle: event.awmWorkflowTitle
          } : {})
        }
        if (event.securityOutcome === 'EXECUTED') {
          msg.writeConfirmed = true
        }
      }
    },
    onContent: (text) => {
      if (messages.value[assistantIdx]) {
        messages.value[assistantIdx].content += text
        if (!showScrollDown.value) scrollToBottom()
      }
    }
  })
}

function contextUsage (msg) {
  return msg?.contextUsage || msg?.meta?.contextUsage || msg?.streamEvent?.contextUsage || null
}

function formatContextUsage (usage) {
  const total = Number(usage?.totalTokens)
  const window = Number(usage?.contextWindow)
  const pct = Number(usage?.utilizationPct)
  if (!Number.isFinite(total) || !Number.isFinite(window)) return '暂无用量'
  const percent = Number.isFinite(pct) ? pct.toFixed(1) : ((total * 100) / window).toFixed(1)
  return `${total.toLocaleString()} / ${window.toLocaleString()} tokens (${percent}%)`
}

function finalizeAssistantMessage (assistantIdx) {
  const msg = messages.value[assistantIdx]
  if (!msg) return
  if (msg.content) {
    msg.content = msg.content
      .replace(/^>\s*\*\*traceId:\*\*\s*`[^`]+`\s*\n\n?/gim, '')
      .replace(/^###\s*追踪信息\s*\n\s*- 追踪 ID:\s*`[^`]+`\s*\n\n?/gim, '')
      .replace(/^\*\*追踪 ID：\*\*\s*`[^`]+`\s*\n\n?/gim, '')
      .replace(/^追踪\s*ID[：:]\s*`?[a-f0-9-]{8,}`?\s*\n?/gim, '')
  }
  if (!msg.ragHits?.length && msg.meta?.ragHits?.length) msg.ragHits = msg.meta.ragHits
  if (msg.meta?.writeConfirmed || msg.meta?.securityOutcome === 'EXECUTED') msg.writeConfirmed = true
  if (hasNoActionableFinding(msg.content)) {
    msg.remediationPlan = null
    msg.planReady = true
    msg.awaitingConfirm = false
    msg.pendingWriteTools = []
    msg.planPhase = 'DIAGNOSE'
  } else if (!msg.writeConfirmed && hasActionableRemediationPlan(msg.content)) {
    msg.remediationPlan = parseRemediationPlan(msg.content)
    msg.planReady = true
  } else if (msg.remediationPlan) {
    msg.planReady = true
  }
  if (!msg.toolsUsed?.length) {
    const extractedTools = extractToolsUsedFromText(msg.content)
    if (extractedTools.length) {
      msg.toolsUsed = extractedTools
    }
  }
}

async function confirmRemediationDialog () {
  await ElMessageBox.confirm(
    '将按上一轮处置计划在路径白名单与策略允许范围内执行写操作（清理、重启等）。此操作可能改变系统状态，是否继续？',
    '确认执行处置',
    {
      type: 'warning',
      confirmButtonText: '确认执行',
      cancelButtonText: '取消',
      closeOnClickModal: false
    }
  )
}

function openKnowledge () {
  window.dispatchEvent(new CustomEvent('ops-navigate-tab', { detail: { tab: 'knowledge' } }))
}

function openSkills () {
  window.dispatchEvent(new CustomEvent('ops-navigate-tab', { detail: { tab: 'agent-skills' } }))
}

const confirmPlanFromMessage = async (index) => {
  confirmPlanIndex.value = index
  try {
    await confirmRemediationDialog()
    const prior = messages.value[index]
    if (prior) {
      prior.writeConfirmed = true
      prior.awaitingConfirm = false
      prior.meta = { ...(prior.meta || {}), writeConfirmed: true, awaitingConfirm: false }
    }
    skipRemediationConfirm.value = true
    const tools = Array.isArray(prior?.plannedTools) ? prior.plannedTools.filter(Boolean) : []
    inputMessage.value = tools.length
      ? `确认执行\n请按上一轮计划落地：${tools.join('、')}`
      : '确认执行'
    await handleSend()
  } catch {
    // cancelled
  } finally {
    confirmPlanIndex.value = -1
    skipRemediationConfirm.value = false
  }
}

const runNaturalLanguageStream = async (message) => {
  const prior = messages.value.slice(0, -1)
  const history = buildConversationHistory(prior)
  const assistantIdx =
    messages.value.push({
      role: 'assistant',
      content: '',
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    }) - 1
  const token = generationToken
  loading.value = true
  streaming.value = true
  streamAbortController = new AbortController()

  try {
    const response = await assistantChatStream(message, history, {
      useToolAgent: !userDeclinesTools(message),
      confirmRemediation: userConfirmedRemediation(message),
      modelProfile: selectedModelProfile.value,
      signal: streamAbortController.signal
    })
    if (isGenerationCancelled(token)) return
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        const errText = await response.text().catch(() => '')
        let hint = '未登录或会话已失效，请退出后重新登录。'
        try {
          const j = JSON.parse(errText)
          if (j.securityCode === 'CSRF_ORIGIN_DENIED') {
            hint = '请求被 CSRF 防护拦截（常见于通过局域网 IP 访问）。请刷新页面或重新构建前端；管理员可在 application.yml 的 app.cors.allowed-origin-patterns 加入当前访问地址。'
          } else if (j.message) {
            hint = j.message
          }
        } catch {
          /* ignore */
        }
        messages.value[assistantIdx].content = `助手暂时不可用: ${hint}`
        return
      }
      const errText = await response.text().catch(() => '')
      let errMsg = `请求失败 (${response.status})`
      try {
        const j = JSON.parse(errText)
        if (j.message) errMsg = j.message
      } catch {
        if (errText) errMsg = errText.slice(0, 400)
      }
      messages.value[assistantIdx].content = '助手暂时不可用: ' + errMsg
      return
    }
    const reader = response.body?.getReader()
    if (!reader) {
      messages.value[assistantIdx].content = '服务端未返回流式数据，请确认 AI 已配置。'
      return
    }
    streamReader = reader
    const decoder = new TextDecoder('utf-8')
    let sseBuf = ''
    while (true) {
      if (isGenerationCancelled(token)) break
      const { done, value } = await reader.read()
      if (done) break
      if (isGenerationCancelled(token)) break
      const chunk = decoder.decode(value, { stream: true })
      sseBuf = appendSsePayloadFromChunk(sseBuf, chunk, (payload) => {
        applySseToAssistant(assistantIdx, payload)
      })
    }
    if (!isGenerationCancelled(token)) {
      flushSseBuffer(sseBuf, (payload) => {
        applySseToAssistant(assistantIdx, payload)
      })
      if (!messages.value[assistantIdx].content.trim()) {
        messages.value[assistantIdx].content = '（未返回内容，请检查模型服务）'
      }
      finalizeAssistantMessage(assistantIdx)
    }
  } catch (e) {
    if (isGenerationCancelled(token) || e?.name === 'AbortError') return
    console.error('自然语言对话失败', e)
    const errHint =
      '\n\n—\n连接助手失败: ' + (e.message || String(e)) + '\n请确认后端服务已启动且模型配置正确。'
    const existing = (messages.value[assistantIdx].content || '').trim()
    messages.value[assistantIdx].content = existing
      ? existing + errHint
      : '连接助手失败: ' + (e.message || String(e)) + '\n请确认后端服务已启动且模型配置正确。'
  } finally {
    if (!isGenerationCancelled(token)) {
      streaming.value = false
      loading.value = false
    }
    streamAbortController = null
    streamReader = null
  }
  scrollToBottom()
}

const handleSend = async () => {
  const rawInput = inputMessage.value.trim()
  let message = buildDraftMessage(rawInput)
  if (pendingAttachment.value) {
    message = `${rawInput}\n\n> 附件: ${pendingAttachment.value.name}\n${pendingAttachment.value.text}`.trim()
  }
  if (!message || loading.value) return

  inputMessage.value = ''
  pendingAttachment.value = null
  messages.value.push({
    role: 'user',
    content: message,
    timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  })
  scrollToBottom()

  if (userConfirmedRemediation(message) && !skipRemediationConfirm.value) {
    try {
      await confirmRemediationDialog()
    } catch {
      messages.value.push({
        role: 'assistant',
        content: '已取消执行。',
        timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      })
      scrollToBottom()
      return
    }
  }

  if (isWorkbenchHighRiskMessage(message)) {
    loading.value = true
    try {
      const code = classifyWorkbenchBlockCode(message)
      // 前端抢先拦截时后端收不到请求，必须显式上报否则「安全教训」永远为空
      captureOpsFailureInsight({
        userInput: message,
        securityCode: code,
        detail: 'workbench local high-risk block'
      }).catch(() => {})
      messages.value.push({
        role: 'assistant',
        content: workbenchHighRiskBlockMarkdown(message),
        timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
      })
      scrollToBottom()
    } finally {
      loading.value = false
    }
    return
  }

  try {
    await runNaturalLanguageStream(message)
  } catch (error) {
    console.error('助手流式对话失败:', error)
    messages.value.push({
      role: 'assistant',
      content: `连接助手失败：${error.message || String(error)}\n\n请检查后端服务、AI 配置和当前登录状态。`,
      timestamp: new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    })
    scrollToBottom()
  }
}

const sendQuickCommand = (command) => {
  inputMessage.value = command
  return handleSend()
}

function applyPrefill(text) {
  if (typeof text !== 'string' || !text.trim()) return
  inputMessage.value = text.trim()
  nextTick(() => scrollToBottom())
}

  onUnmounted(() => {
  stopGeneration()
  if (patrolPollTimer) {
    clearInterval(patrolPollTimer)
  }
  window.removeEventListener('ops-patrol-pending-change', handlePatrolPendingEvent)
  if (speechRecognition) {
    try {
      speechRecognition.stop()
    } catch {
      /* noop */
    }
  }
})

const clearChat = () => {
  clearSession()
}

defineExpose({ applyPrefill, clearChat })
</script>

<style scoped>
.ops-chat-container {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  background: var(--ops-panel);
  border: 1px solid var(--ops-border);
  border-radius: var(--ops-radius);
  box-shadow: var(--ops-shadow-sm);
  overflow: hidden;
}

.ops-chat-container--agent {
  position: relative;
  flex: 1;
  min-height: calc(100vh - var(--ops-header-h, 52px));
  width: 100%;
  background: var(--agent-surface);
  border: none;
  border-radius: 0;
  box-shadow: none;
  overflow: hidden;
}

.composer-sticky {
  flex-shrink: 0;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 12px 0 20px;
  background: #fff;
}

.messages-inner {
  display: flex;
  flex-direction: column;
}

.composer-sticky {
  flex-shrink: 0;
  padding-bottom: 8px;
  background: linear-gradient(180deg, transparent, var(--agent-surface) 24%);
}

.scroll-down-btn {
  position: absolute;
  left: 50%;
  bottom: 140px;
  transform: translateX(-50%);
  z-index: 5;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: 1px solid var(--agent-border);
  background: var(--agent-surface);
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: var(--agent-muted);
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.scroll-down-btn:hover {
  transform: translateX(-50%) translateY(-2px);
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.16);
  color: var(--ops-primary);
}

.tool-calling {
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 14px;
  padding: 12px 14px;
  margin-bottom: 12px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.tool-calling-header {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #374151;
  font-weight: 500;
  font-size: 14px;
}

.tool-calling-header::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #14b8a6;
  animation: prosePulse 1.2s ease-in-out infinite;
  flex-shrink: 0;
}

.tool-command {
  margin-top: 0;
  background: #1a1b1e;
  border-radius: 10px;
  padding: 10px 12px;
  border: 1px solid #2d2d2d;
}

.tool-command code {
  color: #d4d4d4;
  font-family: 'Courier New', monospace;
  font-size: 12px;
}

.agent-think-panel {
  margin: 0 0 10px;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid rgba(13, 148, 136, 0.22);
  background: linear-gradient(180deg, #f0fdfa, #f8fafc);
  color: #0f766e;
  font-size: 13px;
}

.agent-think-panel.is-live {
  border-color: rgba(13, 148, 136, 0.4);
  box-shadow: 0 0 0 1px rgba(13, 148, 136, 0.08);
}

.agent-think-panel__title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  margin-bottom: 8px;
}

.agent-think-panel__list {
  margin: 0;
  padding-left: 18px;
  color: #334155;
  line-height: 1.6;
}

.agent-think-panel__hint {
  margin: 0;
  color: #64748b;
}

.agent-stream-status {
  padding: 4px 0 8px;
}

.agent-stream-status__inner {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(240, 253, 250, 0.95);
  border: 1px solid rgba(13, 148, 136, 0.2);
  color: #0f766e;
  font-size: 13px;
  font-weight: 500;
}

.assistant-context-usage {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin: 8px 0 0 42px;
  color: #64748b;
  font-size: 11px;
}

.assistant-context-usage__label {
  color: #0f766e;
  font-weight: 600;
}

.assistant-context-usage__model {
  padding-left: 6px;
  border-left: 1px solid #cbd5e1;
  color: #94a3b8;
}

.agent-model-switcher {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 8px;
}

.agent-model-switcher__label {
  color: #64748b;
  font-size: 12px;
}

.agent-model-switcher__select {
  width: 220px;
}

</style>
