import { ref, watch } from 'vue'

const STORAGE_KEY = 'threshcore-agent-sessions-v2'
const MAX_SESSIONS = 24
const MAX_MESSAGES = 80
const PERSIST_DEBOUNCE_MS = 800

const sessions = ref([])
const activeSessionId = ref('')
const messages = ref([])
let storeLoaded = false
let persistTimer = null

function deriveTitle(msgs) {
  const first = (msgs || []).find(m => m.role === 'user' && m.content)
  if (!first) return '新任务'
  const text = String(first.content).replace(/\s+/g, ' ').trim()
  return text.length > 28 ? `${text.slice(0, 28)}…` : text
}

function trimSessionMessages(session) {
  if (!session || !Array.isArray(session.messages)) return
  if (session.messages.length > MAX_MESSAGES) {
    session.messages.splice(0, session.messages.length - MAX_MESSAGES)
  }
}

function persistNow() {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        activeId: activeSessionId.value,
        sessions: sessions.value.slice(0, MAX_SESSIONS).map(s => {
          trimSessionMessages(s)
          return {
            ...s,
            messages: (s.messages || []).slice(-MAX_MESSAGES)
          }
        })
      })
    )
  } catch {
    /* quota */
  }
}

function schedulePersist() {
  if (persistTimer) clearTimeout(persistTimer)
  persistTimer = setTimeout(() => {
    persistTimer = null
    persistNow()
  }, PERSIST_DEBOUNCE_MS)
}

function activeSession() {
  return sessions.value.find(s => s.id === activeSessionId.value) || null
}

function touchActiveSession() {
  const session = activeSession()
  if (!session) return
  trimSessionMessages(session)
  session.updatedAt = Date.now()
  session.title = deriveTitle(session.messages)
  schedulePersist()
}

function syncMessagesFromActive() {
  const session = activeSession()
  if (!session) {
    messages.value = []
    return
  }
  if (!Array.isArray(session.messages)) {
    session.messages = []
  }
  trimSessionMessages(session)
  messages.value = session.messages
}

function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return false
    const data = JSON.parse(raw)
    if (!Array.isArray(data?.sessions) || data.sessions.length === 0) return false
    sessions.value = data.sessions.map(s => ({
      id: s.id || `s-${Date.now()}`,
      title: s.title || '新任务',
      updatedAt: s.updatedAt || Date.now(),
      messages: Array.isArray(s.messages) ? s.messages.slice(-MAX_MESSAGES) : []
    }))
    activeSessionId.value = data.activeId || sessions.value[0].id
    syncMessagesFromActive()
    return true
  } catch {
    return false
  }
}

function ensureDefaultSession() {
  if (sessions.value.length === 0) {
    const id = `s-${Date.now()}`
    const session = { id, title: '新任务', updatedAt: Date.now(), messages: [] }
    sessions.value = [session]
    activeSessionId.value = id
    messages.value = session.messages
    persistNow()
    return
  }
  if (!activeSessionId.value || !sessions.value.some(s => s.id === activeSessionId.value)) {
    activeSessionId.value = sessions.value[0].id
    syncMessagesFromActive()
  }
}

let watchBound = false

function bindWatchers() {
  if (watchBound) return
  watchBound = true
  watch(messages, () => touchActiveSession(), { deep: true })
}

export function useAgentSession() {
  if (!storeLoaded) {
    storeLoaded = true
    if (!loadFromStorage()) {
      ensureDefaultSession()
    } else {
      ensureDefaultSession()
    }
    bindWatchers()
  }

  function loadSession() {
    if (!loadFromStorage()) ensureDefaultSession()
    else ensureDefaultSession()
  }

  function createSession() {
    const id = `s-${Date.now()}`
    const session = {
      id,
      title: '新任务',
      updatedAt: Date.now(),
      messages: []
    }
    sessions.value.unshift(session)
    if (sessions.value.length > MAX_SESSIONS) {
      sessions.value = sessions.value.slice(0, MAX_SESSIONS)
    }
    activeSessionId.value = id
    messages.value = session.messages
    persistNow()
    return id
  }

  function switchSession(id) {
    if (!sessions.value.some(s => s.id === id)) return
    touchActiveSession()
    activeSessionId.value = id
    syncMessagesFromActive()
    persistNow()
  }

  function deleteSession(id) {
    const idx = sessions.value.findIndex(s => s.id === id)
    if (idx < 0) return
    sessions.value.splice(idx, 1)
    if (activeSessionId.value === id) {
      if (sessions.value.length === 0) {
        createSession()
      } else {
        activeSessionId.value = sessions.value[0].id
        syncMessagesFromActive()
      }
    }
    persistNow()
  }

  function clearSession() {
    if (messages.value) {
      messages.value.splice(0, messages.value.length)
    }
    persistNow()
  }

  return {
    sessions,
    activeSessionId,
    messages,
    loadSession,
    clearSession,
    createSession,
    switchSession,
    deleteSession
  }
}
