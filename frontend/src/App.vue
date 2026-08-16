<template>

  <div>

    <Login v-if="!isLoggedIn" @login-success="handleLoginSuccess" />



    <div v-else class="app-shell" :class="{ 'app-shell--agent': isAgentWorkspace }">

      <div
        v-if="isMobile && !isSidebarCollapsed"
        class="sidebar-backdrop"
        aria-hidden="true"
        @click="isSidebarCollapsed = true"
      />

      <AgentSidebar

        :active-tab="activeTab"

        :collapsed="isSidebarCollapsed"

        :nav-groups="visibleNavGroups"

        :sessions="agentSessions"

        :active-session-id="activeSessionId"

        @select="handleSelect"

        @new-chat="handleAgentNewChat"

        @select-session="handleSelectSession"

        @delete-session="handleDeleteSession"

        @toggle-collapse="toggleSidebar"

      />



      <div

        class="shell-main"

        :class="{

          collapsed: isSidebarCollapsed,

          'shell-main--agent': isAgentWorkspace

        }"

      >

        <header class="shell-topbar" :class="{ 'shell-topbar--minimal': isAgentWorkspace }">

          <div class="topbar-left">

            <el-button

              v-if="isMobile"

              text

              circle

              size="small"

              :icon="isSidebarCollapsed ? Menu : Close"

              @click="toggleSidebar"

            />

            <div class="topbar-title">
              <h1>{{ breadcrumbText }}</h1>
            </div>
          </div>

          <div class="topbar-right">
            <el-popover
              v-if="isAgentWorkspace && !isMobile"
              placement="bottom-end"
              :width="320"
              trigger="click"
              popper-class="topbar-status-popover"
            >
              <template #reference>
                <el-button class="topbar-status-btn" size="small" text type="primary">
                  <el-icon><Odometer /></el-icon>
                  运行状态
                </el-button>
              </template>
              <RuntimeModeBar compact show-refresh />
            </el-popover>

            <el-popover
              v-if="!isAgentWorkspace && !isMobile"
              placement="bottom-end"
              :width="380"
              trigger="click"
              popper-class="topbar-perf-popover"
            >
              <template #reference>
                <el-button class="topbar-perf-btn" size="small" text type="primary">
                  <el-icon><Odometer /></el-icon>
                  本机态势
                </el-button>
              </template>
              <TopPerformanceBar layout="vertical" />
            </el-popover>

            <el-button

              v-if="isAgentWorkspace && isMobile"

              text

              circle

              size="small"

              :icon="Odometer"

              title="本机负载"

              @click="contextDrawerOpen = true"

            />

            <div v-if="!isAgentWorkspace" class="toolbar-actions">
              <RuntimeModeBar compact />

              <el-tooltip placement="bottom" content="快捷键：1 对话 · 2 工具 · 3 审计 · 4 链路 · 5 日志 · 6 历史 · 7 看板 · 8 配置">

                <el-button text circle size="small" :icon="QuestionFilled" aria-label="快捷键说明" />

              </el-tooltip>

            </div>



            <el-dropdown @command="handleUserMenuCommand">

              <span class="user-info">

                <el-avatar size="small" :src="userAvatar" />

                <span class="user-text">

                  <span class="user-name">{{ username }}</span>

                  <el-tag size="small" effect="plain" class="role-pill">{{ roleBadgeText }}</el-tag>

                </span>

                <el-icon class="el-icon--right"><ArrowDown /></el-icon>

              </span>

              <template #dropdown>

                <el-dropdown-menu>

                  <el-dropdown-item disabled>{{ username }} · {{ roleBadgeText }}</el-dropdown-item>

                  <el-dropdown-item command="profile">个人中心</el-dropdown-item>

                  <el-dropdown-item command="system-config">系统配置</el-dropdown-item>

                  <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>

                </el-dropdown-menu>

              </template>

            </el-dropdown>

          </div>

        </header>



        <div class="workspace-body" :class="{ 'workspace-body--agent': isAgentWorkspace }">

          <main
            class="shell-content main-content"
            :class="{ 'shell-content--agent': isAgentWorkspace }"
          >
            <div class="page-viewport" :class="{ 'page-viewport--agent': isAgentWorkspace }">
              <transition name="page-fade" mode="out-in">
                <component
                  :is="currentComponent"
                  :key="activeTab"
                  ref="componentRef"
                  :initial-task-id="targetTaskId"
                  @view-task="handleViewTask"
                  @profile-updated="handleProfileUpdated"
                  @open-path-policy="handleOpenPathPolicy"
                />
              </transition>
            </div>
          </main>



          <OpsContextPanel v-if="isAgentWorkspace && !isMobile" />

        </div>

      </div>



      <el-drawer

        v-model="contextDrawerOpen"

        destroy-on-close

        title="本机负载"

        direction="rtl"

        size="85%"

        class="context-drawer"

      >

        <OpsContextPanel embedded />

      </el-drawer>

    </div>

  </div>

</template>



<script setup>

import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

import {

  ArrowDown,

  ChatLineSquare,

  Clock,

  Close,

  Connection,

  DataLine,

  Document,

  Grid,

  List,

  Menu,


  Odometer,

  PieChart,

  Platform,

  QuestionFilled,

  Reading,

  Setting,


  Collection,


  Lock,

  User

} from '@element-plus/icons-vue'

import Login from './components/Login.vue'

import AgentSidebar from './components/agent/AgentSidebar.vue'

import OpsContextPanel from './components/agent/OpsContextPanel.vue'

import TopPerformanceBar from './components/TopPerformanceBar.vue'
import RuntimeModeBar from './components/RuntimeModeBar.vue'
import OpsChatInterface from './components/OpsChatInterface.vue'
import AgentSkillsPage from './components/agent/AgentSkillsPage.vue'

import { ElMessage } from 'element-plus'
import { logout, getUserInfo } from './api'
import { useAgentSession } from './composables/useAgentSession'

const {
  sessions: agentSessions,
  activeSessionId,
  createSession,
  switchSession,
  deleteSession
} = useAgentSession()



const defineTabAsyncComponent = (loader) =>

  defineAsyncComponent({

    loader,

    suspensible: false

  })



const LogAnalysis = defineTabAsyncComponent(() => import('./components/LogAnalysis.vue'))

const HistoryList = defineTabAsyncComponent(() => import('./components/HistoryList.vue'))

const LogAnalysisDashboard = defineTabAsyncComponent(() => import('./components/LogAnalysisDashboard.vue'))

const ToolConsole = defineTabAsyncComponent(() => import('./components/ToolConsole.vue'))

const AuditCenter = defineTabAsyncComponent(() => import('./components/AuditCenter.vue'))

const SecurityCockpit = defineTabAsyncComponent(() => import('./components/SecurityCockpit.vue'))

const SystemConfigCenter = defineTabAsyncComponent(() => import('./components/SystemConfigCenter.vue'))

const UserProfileCenter = defineTabAsyncComponent(() => import('./components/UserProfileCenter.vue'))

const KnowledgeBase = defineTabAsyncComponent(() => import('./components/KnowledgeBase.vue'))

const EnvironmentStatus = defineTabAsyncComponent(() => import('./components/EnvironmentStatus.vue'))

const OpsTraceCenter = defineTabAsyncComponent(() => import('./components/OpsTraceCenter.vue'))



const isLoggedIn = ref(false)

const activeTab = ref('ops-chat')

const componentRef = ref(null)

const targetTaskId = ref('')

const userAvatar = ref('')

const username = ref('本地管理员')

const userRole = ref(0)

const isSidebarCollapsed = ref(false)

const isMobile = ref(typeof window !== 'undefined' ? window.innerWidth < 768 : false)

const contextDrawerOpen = ref(false)



const tabConfig = {

  'ops-chat': { label: '运维对话', component: OpsChatInterface, icon: ChatLineSquare },

  'agent-skills': { label: '工具与技能', component: AgentSkillsPage, icon: Grid },

  'mcp-tools': { label: 'MCP 工具台', component: ToolConsole, icon: Connection },

  audit: { label: '统一审计中心', component: AuditCenter, icon: List },

  'ops-audit-trace': { label: '统一审计中心', component: AuditCenter, icon: List },

  'ops-trace': { label: 'AWM 记忆与链路', component: OpsTraceCenter, icon: Collection },


  'security-cockpit': { label: '安全驾驶舱', component: SecurityCockpit, icon: Lock },


  'environment-status': { label: '环境状态', component: EnvironmentStatus, icon: Platform },


  analysis: { label: '观测证据', component: LogAnalysis, icon: DataLine },

  history: { label: '任务记录', component: HistoryList, icon: Clock },

  dashboard: { label: '证据趋势看板', component: LogAnalysisDashboard, icon: PieChart },

  knowledge: { label: '知识库', component: KnowledgeBase, icon: Reading },

  'system-config': { label: '系统配置与效果', component: SystemConfigCenter, icon: Setting },

  'profile-center': { label: '个人中心', component: UserProfileCenter, icon: User }

}



const defaultTab = 'ops-chat'

const NAV_GROUP_ORDER = [

  { title: '任务 · 工具 · 审计', keys: ['ops-chat', 'agent-skills', 'mcp-tools', 'audit', 'ops-trace', 'security-cockpit', 'environment-status'] },

  { title: '观测与证据', keys: ['analysis', 'history', 'dashboard', 'knowledge'] },

  { title: '账号与配置', keys: ['system-config', 'profile-center'] }

]



const visibleNavGroups = computed(() =>

  NAV_GROUP_ORDER.map(group => ({

    title: group.title,

    items: group.keys

      .map(key => {

        const cfg = tabConfig[key]

        if (!cfg) return null

        return { key, label: cfg.label, icon: cfg.icon }

      })

      .filter(Boolean)

  }))

)



const shortcutTabMap = {

  '1': 'ops-chat',

  '2': 'mcp-tools',

  '3': 'audit',

  '4': 'ops-trace',

  '5': 'analysis',

  '6': 'history',

  '7': 'dashboard',

  '8': 'system-config'

}



const isAgentWorkspace = computed(() => activeTab.value === 'ops-chat' || activeTab.value === 'agent-skills')

const roleBadgeText = computed(() => (userRole.value === 1 ? '管理员' : '普通用户'))

const breadcrumbText = computed(() => tabConfig[activeTab.value]?.label || tabConfig[defaultTab].label)

const currentComponent = computed(() => tabConfig[activeTab.value]?.component || tabConfig[defaultTab].component)



function toggleSidebar() {

  isSidebarCollapsed.value = !isSidebarCollapsed.value

}



function handleResize() {

  isMobile.value = window.innerWidth < 768

  if (isMobile.value) {

    isSidebarCollapsed.value = true

  }

}



function syncUserState(userData = {}) {

  username.value = userData.username || '本地管理员'

  userRole.value = Number(userData.role ?? 0)

}



async function validateSession() {
  try {
    const data = await getUserInfo()
    if (data) {
      syncUserState(data)
      try {
        const raw = localStorage.getItem('user')
        const prev = raw ? JSON.parse(raw) : {}
        localStorage.setItem('user', JSON.stringify({ ...prev, ...data }))
      } catch {
        // ignore localStorage sync errors
      }
    }
  } catch {
    localStorage.removeItem('user')
    isLoggedIn.value = false
    ElMessage.warning('会话已失效，请重新登录')
  }
}

function checkLoginStatus() {

  const rawUser = localStorage.getItem('user')

  if (!rawUser) {

    isLoggedIn.value = false

    return

  }

  try {

    const userData = JSON.parse(rawUser)

    // 先乐观放行首屏，再异步探针校验会话
    isLoggedIn.value = true

    syncUserState(userData)

    if (!tabConfig[activeTab.value]) {

      activeTab.value = defaultTab

    }

    validateSession()

  } catch {

    localStorage.removeItem('user')

    isLoggedIn.value = false

  }

}



function handleLoginSuccess() {

  checkLoginStatus()

  activeTab.value = defaultTab

}



async function handleLogout() {

  try {

    await logout()

  } catch (_) {

    // ignore

  }

  localStorage.removeItem('user')

  isLoggedIn.value = false

}



function handleUserMenuCommand(command) {

  if (command === 'profile') {

    activeTab.value = 'profile-center'

    return

  }

  if (command === 'system-config') {

    activeTab.value = 'system-config'

    return

  }

  if (command === 'logout') {

    handleLogout()

  }

}



function callComponentMethod(methodName, args = [], attempts = 12) {

  nextTick(() => {

    const fn = componentRef.value?.[methodName]

    if (typeof fn === 'function') {

      fn(...args)

      return

    }

    if (attempts <= 0) return

    setTimeout(() => callComponentMethod(methodName, args, attempts - 1), 100)

  })

}



function handleOpsNavigateAgent(event) {

  const message = event.detail?.message

  if (typeof message !== 'string' || !message.trim()) return

  activeTab.value = 'ops-chat'

  callComponentMethod('applyPrefill', [message.trim()])

}



function handleSelect(key) {

  activeTab.value = key

  if (isMobile.value) {

    isSidebarCollapsed.value = true

  }

}



function handleAgentNewChat() {

  createSession()

  activeTab.value = 'ops-chat'

}



function handleSelectSession(id) {

  switchSession(id)

  activeTab.value = 'ops-chat'

}



function handleDeleteSession(id) {

  deleteSession(id)

}



function handleGlobalKeydown(event) {

  const target = event.target

  const tagName = target?.tagName?.toLowerCase?.() || ''

  const isTyping = target?.isContentEditable || tagName === 'input' || tagName === 'textarea'

  if (isTyping || event.ctrlKey || event.altKey || event.metaKey) return



  const targetTab = shortcutTabMap[event.key?.toLowerCase?.() || '']

  if (!targetTab) return

  event.preventDefault()

  activeTab.value = targetTab

}



function handleViewTask(taskId) {

  targetTaskId.value = taskId

  activeTab.value = 'analysis'

  nextTick(() => {

    callComponentMethod('loadTask', [taskId])

    targetTaskId.value = ''

  })

}



function handleOpsNavigateTab(event) {

  const tab = event?.detail?.tab

  const section = event?.detail?.section

  const traceId = event?.detail?.traceId

  if (tab && tabConfig[tab]) {

    activeTab.value = tab

    if (section && tab === 'system-config') {

      callComponentMethod('focusSection', [section])

    }

    if (traceId && (tab === 'audit' || tab === 'ops-audit-trace' || tab === 'ops-trace')) {

      callComponentMethod('openTrace', [traceId])

    }

  }

}



function handleOpenPathPolicy() {

  window.dispatchEvent(new CustomEvent('ops-navigate-tab', {

    detail: { tab: 'system-config', section: 'pathPolicy' }

  }))

}



function handleProfileUpdated(user) {

  syncUserState(user || {})

}



onMounted(() => {

  checkLoginStatus()

  handleResize()

  window.addEventListener('resize', handleResize)

  window.addEventListener('keydown', handleGlobalKeydown)

  window.addEventListener('ops-navigate-agent', handleOpsNavigateAgent)

  window.addEventListener('ops-navigate-tab', handleOpsNavigateTab)

})



onUnmounted(() => {

  window.removeEventListener('resize', handleResize)

  window.removeEventListener('keydown', handleGlobalKeydown)

  window.removeEventListener('ops-navigate-agent', handleOpsNavigateAgent)

  window.removeEventListener('ops-navigate-tab', handleOpsNavigateTab)

})



watch(isMobile, value => {

  isSidebarCollapsed.value = value

})



watch(

  visibleNavGroups,

  groups => {

    const visibleKeys = new Set(groups.flatMap(group => group.items.map(item => item.key)))

    if (

      !visibleKeys.has(activeTab.value)

      && activeTab.value !== 'profile-center'

      && activeTab.value !== 'system-config'

    ) {

      activeTab.value = defaultTab

    }

  },

  { flush: 'sync' }

)

</script>



<style scoped>

.app-shell {

  min-height: 100vh;

  display: flex;

  background: var(--shell-bg);

  font-family: var(--ops-font);

}



.shell-main {

  flex: 1;

  margin-left: var(--agent-sidebar-w);

  width: calc(100vw - var(--agent-sidebar-w));

  display: flex;

  flex-direction: column;

  height: 100vh;

  min-height: 0;

  overflow: hidden;

  transition: margin-left 0.25s ease, width 0.25s ease;

}



.shell-main.collapsed {

  margin-left: var(--agent-sidebar-collapsed-w);

  width: calc(100vw - var(--agent-sidebar-collapsed-w));

}



.shell-main--agent {

  background: var(--agent-surface);

}



.shell-topbar {
  position: sticky;
  top: 0;
  z-index: 999;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  min-height: var(--ops-header-h);
  background: var(--ops-panel);
  border-bottom: 1px solid var(--ops-border);
  box-shadow: var(--ops-shadow-sm);
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  flex: 0 1 auto;
  max-width: min(220px, 28vw);
}

.topbar-title {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar-title span {
  font-size: 11px;
  color: var(--ops-text-subtle);
}

.topbar-right {
  margin-left: auto;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.topbar-title h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ops-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 1;
  min-width: 0;
}

.shell-topbar--minimal {
  padding: 0 20px;
  gap: 16px;
}

.shell-topbar--minimal .topbar-left {
  max-width: none;
  flex: 1;
}

.shell-topbar--minimal .topbar-title h1 {
  font-size: 16px;
  font-weight: 650;
}

.topbar-status-btn,
.topbar-perf-btn {
  flex-shrink: 0;
  font-weight: 500;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 10px 4px 4px;
  border-radius: 10px;
  cursor: pointer;
  background: var(--ops-panel-soft);
  border: 1px solid var(--ops-border);
  color: var(--ops-text);
  flex-shrink: 0;
  max-width: min(200px, 26vw);
}

.user-text {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 88px;
}



.role-pill {

  border-color: rgba(15, 118, 110, 0.18);

  color: #0f766e;

}



.shell-content {

  flex: 1;

  padding: 16px 20px 20px;

  overflow-y: auto;

  background: var(--ops-bg);

}



.shell-content.shell-content--agent {

  display: flex;

  flex-direction: column;

  flex: 1;

  min-height: 0;

  padding: 0 !important;

  overflow: hidden !important;

  background: var(--agent-surface);

}



.page-viewport {

  flex: 1;

  min-height: 0;

}



.page-viewport--agent {

  display: flex;

  flex-direction: column;

  min-height: calc(100vh - var(--ops-header-h, 52px));

  overflow: hidden;

}



.page-viewport--agent :deep(> *) {

  flex: 1;

  min-height: 0;

  display: flex;

  flex-direction: column;

}



.shell-content :deep(.el-card) {

  border-radius: var(--ops-radius);

  border: 1px solid var(--ops-border);

  box-shadow: var(--ops-shadow-sm);

}



.shell-content :deep(.el-card__header) {

  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), var(--ops-panel-soft));

  border-bottom: 1px solid var(--ops-border-soft);

}



.shell-content :deep(.el-table) {

  --el-table-header-bg-color: rgba(245, 247, 251, 0.98);

  --el-table-tr-bg-color: #ffffff;

  --el-table-row-hover-bg-color: rgba(8, 145, 178, 0.05);

  --el-table-border-color: var(--ops-border-soft);

}



.sidebar-backdrop {

  position: fixed;

  inset: 0;

  z-index: 999;

  background: rgba(15, 23, 42, 0.35);

}



:deep(.context-drawer .ops-context-panel) {

  width: 100%;

  border: none;

}



@media (max-width: 768px) {

  .shell-main,

  .shell-main.collapsed {

    margin-left: 0;

    width: 100vw;

  }



  .shell-topbar {

    padding: 0 14px;

  }



  .toolbar-actions {

    display: none;

  }



  .user-text {

    display: none;

  }



  .shell-content {

    padding: 14px;

  }

}

</style>
