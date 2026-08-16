<template>
  <div class="login-container">
    <div class="tc-bg" aria-hidden="true">
      <div class="tc-bg-gradient" />
    </div>

    <div class="tc-shell">
      <aside class="tc-hero">
        <div class="tc-hero-mark">
          <div class="tc-hero-logo-wrap" aria-hidden="true">
            <img
              src="/threshcore-logo.png"
              alt=""
              class="tc-hero-logo"
            />
          </div>
          <div class="tc-hero-copy">
            <h1 class="tc-hero-title">ThreshCore</h1>
            <p class="tc-hero-tag">智能运维与日志洞察</p>
          </div>
        </div>
        <p class="tc-hero-lead">
          账号密码登录；任务告警在平台内记录与查看。
        </p>
        <ul class="tc-hero-list">
          <li>本地与受控节点协同</li>
          <li>任务、报告与链路可追溯</li>
          <li>对话与工具在策略内执行</li>
        </ul>
        <div class="tc-hero-foot">ThreshCore · 智能运维与日志分析平台</div>
      </aside>

      <main class="tc-card">
        <div class="tc-seg" role="tablist" aria-label="登录或注册">
          <button
            type="button"
            role="tab"
            :aria-selected="!isRegister"
            class="tc-seg-btn"
            :class="{ active: !isRegister }"
            @click="isRegister = false"
          >
            登录
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="isRegister"
            class="tc-seg-btn"
            :class="{ active: isRegister }"
            @click="isRegister = true"
          >
            注册
          </button>
        </div>

        <!-- 登录表单 -->
        <div v-show="!isRegister" class="form-section tc-form-section">
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><User /></el-icon>
              </div>
              <input 
                type="text" 
                placeholder="请输入账号" 
                v-model="loginForm.username"
                @input="validateAccount('login')"
                class="form-input"
              >
            </div>
            <div class="validate-tip" :class="{show: loginForm.accountTip.show, error: loginForm.accountTip.error, success: loginForm.accountTip.success}">
              <el-icon v-if="loginForm.accountTip.error"><Close /></el-icon>
              <el-icon v-else-if="loginForm.accountTip.success"><Check /></el-icon>
              {{ loginForm.accountTip.text }}
            </div>
          </div>
          
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><Lock /></el-icon>
              </div>
              <div class="password-input-wrapper">
                <input 
                  :type="showLoginPassword ? 'text' : 'password'" 
                  placeholder="请输入密码" 
                  v-model="loginForm.password"
                  @input="validatePassword('login')"
                  class="form-input password-input"
                >
                <button type="button" class="password-toggle" @click="toggleLoginPassword">
                  <el-icon v-if="showLoginPassword"><View /></el-icon>
                  <el-icon v-else><Hide /></el-icon>
                </button>
              </div>
            </div>
            <div class="validate-tip" :class="{show: loginForm.pwdTip.show, error: loginForm.pwdTip.error, success: loginForm.pwdTip.success}">
              <el-icon v-if="loginForm.pwdTip.error"><Close /></el-icon>
              <el-icon v-else-if="loginForm.pwdTip.success"><Check /></el-icon>
              {{ loginForm.pwdTip.text }}
            </div>
            <!-- 密码强度条 -->
            <div class="password-strength" :class="{show: loginForm.password.trim() !== ''}">
              <div class="strength-bar" :class="loginForm.strengthClass[0]"></div>
              <div class="strength-bar" :class="loginForm.strengthClass[1]"></div>
              <div class="strength-bar" :class="loginForm.strengthClass[2]"></div>
            </div>
          </div>
          
          <div class="form-footer">
            <div class="agreement">
              <label class="checkbox-container">
                <input type="checkbox" v-model="loginForm.agree">
                <span class="checkmark"></span>
                <span class="label-text">我已阅读并同意<a href="#" class="agreement-link" @click.prevent="agreementVisible = true">《用户服务协议》</a></span>
              </label>
            </div>
            <div class="forgot-password">
              <a href="javascript:;" class="forgot-link" @click="showForgotPassword = true">忘记密码？</a>
            </div>
          </div>
          
          <button class="btn login-btn" @click="handleLogin" :disabled="!loginForm.agree">
            <span>登录</span>
            <el-icon class="btn-icon"><ArrowRight /></el-icon>
          </button>
        </div>

        <!-- 注册表单 -->
        <div v-show="isRegister" class="form-section tc-form-section">
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><User /></el-icon>
              </div>
              <input 
                type="text" 
                placeholder="请设置账号" 
                v-model="registerForm.username"
                @input="validateAccount('register')"
                class="form-input"
              >
            </div>
            <div class="validate-tip" :class="{show: registerForm.accountTip.show, error: registerForm.accountTip.error, success: registerForm.accountTip.success}">
              <el-icon v-if="registerForm.accountTip.error"><Close /></el-icon>
              <el-icon v-else-if="registerForm.accountTip.success"><Check /></el-icon>
              {{ registerForm.accountTip.text }}
            </div>
          </div>
          
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><Lock /></el-icon>
              </div>
              <div class="password-input-wrapper">
                <input 
                  :type="showRegisterPassword ? 'text' : 'password'" 
                  placeholder="请设置密码（8位以上，需包含数字、英文、特殊字符中至少两类）" 
                  v-model="registerForm.password"
                  @input="validatePassword('register')"
                  class="form-input password-input"
                >
                <button type="button" class="password-toggle" @click="toggleRegisterPassword">
                  <el-icon v-if="showRegisterPassword"><View /></el-icon>
                  <el-icon v-else><Hide /></el-icon>
                </button>
              </div>
            </div>
            <div class="validate-tip" :class="{show: registerForm.pwdTip.show, error: registerForm.pwdTip.error, success: registerForm.pwdTip.success}">
              <el-icon v-if="registerForm.pwdTip.error"><Close /></el-icon>
              <el-icon v-else-if="registerForm.pwdTip.success"><Check /></el-icon>
              {{ registerForm.pwdTip.text }}
            </div>
            <!-- 密码强度条 -->
            <div class="password-strength" :class="{show: registerForm.password.trim() !== ''}">
              <div class="strength-bar" :class="registerForm.strengthClass[0]"></div>
              <div class="strength-bar" :class="registerForm.strengthClass[1]"></div>
              <div class="strength-bar" :class="registerForm.strengthClass[2]"></div>
            </div>
          </div>
          
          <div class="form-group">
            <div class="input-container">
              <div class="input-icon">
                <el-icon><Check /></el-icon>
              </div>
              <div class="password-input-wrapper">
                <input 
                  :type="showRegisterRepwd ? 'text' : 'password'" 
                  placeholder="请确认密码" 
                  v-model="registerForm.repwd"
                  @input="validateRepwd"
                  class="form-input password-input"
                >
                <button type="button" class="password-toggle" @click="toggleRegisterRepwd">
                  <el-icon v-if="showRegisterRepwd"><View /></el-icon>
                  <el-icon v-else><Hide /></el-icon>
                </button>
              </div>
            </div>
            <div class="validate-tip" :class="{show: registerForm.repwdTip.show, error: registerForm.repwdTip.error, success: registerForm.repwdTip.success}">
              <el-icon v-if="registerForm.repwdTip.error"><Close /></el-icon>
              <el-icon v-else-if="registerForm.repwdTip.success"><Check /></el-icon>
              {{ registerForm.repwdTip.text }}
            </div>
          </div>
          
          <div class="agreement">
            <label class="checkbox-container" :class="{ 'checkbox-readonly': !protocolReadConfirmed }">
              <input type="checkbox" v-model="registerForm.agree" :disabled="!protocolReadConfirmed">
              <span class="checkmark"></span>
              <span class="label-text">我已阅读并同意<a href="#" class="agreement-link" @click.prevent="agreementVisible = true">《用户服务协议》</a></span>
            </label>
            <p v-if="!protocolReadConfirmed" class="agreement-prereq">请先完成《用户服务协议》全文阅读并确认，再勾选此项。</p>
          </div>
          
          <button class="btn register-btn" @click="handleRegister" :disabled="!registerForm.agree || !protocolReadConfirmed">
            <span>创建账号</span>
            <el-icon class="btn-icon"><Check /></el-icon>
          </button>
        </div>
      </main>
    </div>
  </div>
  
  <!-- 忘记密码说明 -->
  <el-dialog
    v-model="showForgotPassword"
    title="忘记密码"
    width="400px"
    :close-on-click-modal="false"
    class="forgot-password-dialog"
  >
    <p class="forgot-password-hint">
      请联系管理员重置密码。出于安全考虑，登录页不提供自助重置；登录后可在「个人中心 → 账号安全」修改密码。
    </p>
    <template #footer>
      <div class="dialog-footer">
        <button class="btn reset-btn" @click="showForgotPassword = false">
          <span>知道了</span>
        </button>
      </div>
    </template>
  </el-dialog>

  <UserServiceAgreement v-model="agreementVisible" @read-confirmed="onProtocolReadConfirmed" />
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { User, Lock, Check, Close, View, Hide, ArrowRight } from '@element-plus/icons-vue'
import { login, register } from '../api'
import UserServiceAgreement from './UserServiceAgreement.vue'

const agreementVisible = ref(false)
/** 用户在本页已滑动读完协议并点击确认后，才可勾选同意与登录/注册 */
const protocolReadConfirmed = ref(false)

function onProtocolReadConfirmed() {
  protocolReadConfirmed.value = true
}

// 定义事件
const emit = defineEmits(['login-success'])

// 响应式数据
const isRegister = ref(false)
const showForgotPassword = ref(false)

// 密码显示/隐藏状态
const showLoginPassword = ref(false)
const showRegisterPassword = ref(false)
const showRegisterRepwd = ref(false)

// 登录表单数据
const loginForm = reactive({
  username: '',
  password: '',
  agree: false,
  accountTip: {
    show: false,
    error: false,
    success: false,
    text: '',
    timer: null // 存储自动隐藏定时器
  },
  pwdTip: {
    show: false,
    error: false,
    success: false,
    text: ''
  },
  strengthClass: ['', '', ''] // 密码强度条样式
})

// 注册表单数据
const registerForm = reactive({
  username: '',
  password: '',
  repwd: '',
  agree: false,
  accountTip: {
    show: false,
    error: false,
    success: false,
    text: '',
    timer: null // 存储自动隐藏定时器
  },
  pwdTip: {
    show: false,
    error: false,
    success: false,
    text: ''
  },
  repwdTip: {
    show: false,
    error: false,
    success: false,
    text: ''
  },
  strengthClass: ['', '', ''] // 密码强度条样式
})

// 切换密码显示/隐藏
const toggleLoginPassword = () => {
  showLoginPassword.value = !showLoginPassword.value
}

const toggleRegisterPassword = () => {
  showRegisterPassword.value = !showRegisterPassword.value
}

const toggleRegisterRepwd = () => {
  showRegisterRepwd.value = !showRegisterRepwd.value
}

// 验证账号（取消长度限制，仅限制不能包含中文，正确提示2.5秒自动消失）
const validateAccount = (type) => {
  const form = type === 'login' ? loginForm : registerForm
  const account = form.username.trim()
  
  // 清除之前的自动隐藏定时器，避免重复触发
  if (form.accountTip.timer) clearTimeout(form.accountTip.timer)
  
  form.accountTip.show = false
  form.accountTip.error = false
  form.accountTip.success = false
  form.accountTip.text = ''
  
  if (account === '') return
  
  const chineseReg = /[\u4e00-\u9fa5]/
  const validReg = /^[a-zA-Z0-9!@#$%^&*()_+-=\[\]{};':"\\|,.<>\/?]*$/
  
  if (chineseReg.test(account)) {
    form.accountTip.show = true
    form.accountTip.error = true
    form.accountTip.text = '账号不能包含中文'
  } else if (!validReg.test(account)) {
    form.accountTip.show = true
    form.accountTip.error = true
    form.accountTip.text = '账号只能包含数字、字母和常见特殊字符'
  } else {
    form.accountTip.show = true
    form.accountTip.success = true
    form.accountTip.text = '账号格式正确'
    // 2.5秒后自动隐藏提示
    form.accountTip.timer = setTimeout(() => {
      form.accountTip.show = false
    }, 2500)
  }
}

// 验证密码（8位以上，需包含数字、英文、特殊字符中至少两类）
const validatePassword = (type) => {
  const form = type === 'login' ? loginForm : registerForm
  const password = form.password.trim()
  
  form.pwdTip.show = false
  form.pwdTip.error = false
  form.pwdTip.success = false
  form.pwdTip.text = ''
  form.strengthClass = ['', '', ''] // 重置强度条
  
  if (password === '') return
  
  // 正则定义
  const chineseReg = /[\u4e00-\u9fa5]/
  const validReg = /^[a-zA-Z0-9!@#$%^&*()_+-=\[\]{};':"\\|,.<>\/?]*$/
  const numReg = /\d/ // 数字
  const letterReg = /[a-zA-Z]/ // 英文
  const specialReg = /[!@#$%^&*()_+-=\[\]{};':"\\|,.<>\/?]/ // 特殊字符
  
  // 基础验证
  if (chineseReg.test(password)) {
    form.pwdTip.show = true
    form.pwdTip.error = true
    form.pwdTip.text = '密码不能包含中文'
    return
  }
  
  if (!validReg.test(password)) {
    form.pwdTip.show = true
    form.pwdTip.error = true
    form.pwdTip.text = '密码只能包含数字、字母和常见特殊字符'
    return
  }
  
  // 长度验证（建议8位以上，不强制但提示）
  if (password.length < 8) {
    form.pwdTip.show = true
    form.pwdTip.error = true
    form.pwdTip.text = `密码长度${password.length}/8，建议至少8位`
  } else {
    // 计算符合的字符类型数量
    let typeCount = 0
    if (numReg.test(password)) typeCount++
    if (letterReg.test(password)) typeCount++
    if (specialReg.test(password)) typeCount++
    
    // 验证至少两类
    if (typeCount < 2) {
      form.pwdTip.show = true
      form.pwdTip.error = true
      form.pwdTip.text = '需包含数字、英文、特殊字符中至少两类'
    } else {
      form.pwdTip.show = true
      form.pwdTip.success = true
      // 根据类型数量显示强度
      if (typeCount === 2) {
        form.pwdTip.text = '密码强度：中等'
        form.strengthClass = ['medium', 'medium', '']
      } else {
        form.pwdTip.text = '密码强度：强'
        form.strengthClass = ['strong', 'strong', 'strong']
      }
    }
  }
  
  // 长度过长验证
  if (password.length > 20) {
    form.pwdTip.show = true
    form.pwdTip.error = true
    form.pwdTip.text = '密码长度不能超过20位'
  }
}

// 验证确认密码
const validateRepwd = () => {
  const repwd = registerForm.repwd.trim()
  
  registerForm.repwdTip.show = false
  registerForm.repwdTip.error = false
  registerForm.repwdTip.success = false
  registerForm.repwdTip.text = ''
  
  if (repwd === '') return
  
  if (repwd !== registerForm.password) {
    registerForm.repwdTip.show = true
    registerForm.repwdTip.error = true
    registerForm.repwdTip.text = '两次输入的密码不一致'
  } else {
    registerForm.repwdTip.show = true
    registerForm.repwdTip.success = true
    registerForm.repwdTip.text = '两次输入的密码一致'
  }
}

// 登录处理
const handleLogin = async () => {
  validateAccount('login')
  validatePassword('login')
  
  if (loginForm.username.trim() === '') {
    ElMessage.error('请输入账号')
    return
  }
  if (loginForm.password.trim() === '') {
    ElMessage.error('请输入密码')
    return
  }
  if (!loginForm.agree) {
    ElMessage.error('请勾选同意用户服务协议')
    return
  }
  if (loginForm.accountTip.error || loginForm.pwdTip.error) {
    ElMessage.error('账号或密码格式不正确')
    return
  }

  try {
    // 调用登录 API
    const response = await login({
      username: loginForm.username,
      password: loginForm.password
    })
    
    // 登录成功，保存用户信息到 localStorage（仅保留必要字段）
    localStorage.setItem('user', JSON.stringify({
      userId: response.userId,
      username: response.username,
      role: response.role
    }))
    
    ElMessage.success('登录成功！')
    
    // 发出登录成功事件
    emit('login-success')
  } catch (error) {
    console.error('登录失败:', error)
    // 业务错误（用户名/密码错）已由 axios 拦截器提示；此处仅补网络/后端不可达
    if (!error?.response) {
      ElMessage.error('无法连接后端，请确认 Spring Boot 已在 8088 端口运行')
    }
  }
}

// 注册处理
const handleRegister = async () => {
  validateAccount('register')
  validatePassword('register')
  validateRepwd()
  
  if (registerForm.username.trim() === '') {
    ElMessage.error('请设置账号')
    return
  }
  if (registerForm.password.trim() === '') {
    ElMessage.error('请设置密码')
    return
  }
  if (registerForm.repwd.trim() === '') {
    ElMessage.error('请确认密码')
    return
  }
  if (!protocolReadConfirmed.value) {
    ElMessage.warning('请先阅读《用户服务协议》全文并确认')
    agreementVisible.value = true
    return
  }
  if (!registerForm.agree) {
    ElMessage.error('请勾选同意用户服务协议')
    return
  }
  if (registerForm.accountTip.error || registerForm.pwdTip.error || registerForm.repwdTip.error) {
    ElMessage.error('表单填写有误，请检查')
    return
  }
  
  try {
    // 调用注册 API
    await register({
      username: registerForm.username,
      password: registerForm.password
    })
    
    ElMessage.success('注册成功！')
    
    // 切换到登录表单
    isRegister.value = false
  } catch (error) {
    console.error('注册失败:', error)
    ElMessage.error('注册失败，请稍后重试')
  }
}
</script>

<style scoped>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

.login-container {
  --tc-accent: #0d9488;
  --tc-accent-dim: rgba(13, 148, 136, 0.14);
  --tc-surface: rgba(15, 23, 42, 0.78);
  --tc-border: rgba(148, 163, 184, 0.16);
  --tc-text: #e2e8f0;
  --tc-muted: #94a3b8;
  min-height: 100vh;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(16px, 4vw, 40px);
  font-family: ui-sans-serif, 'Segoe UI', -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: var(--tc-text);
  overflow-x: hidden;
}

.tc-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  background: #020617;
}

.tc-bg-gradient {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 120% 80% at 15% 0%, rgba(13, 148, 136, 0.12), transparent 52%),
    radial-gradient(ellipse 90% 55% at 100% 90%, rgba(45, 212, 191, 0.1), transparent 48%),
    linear-gradient(168deg, #0f172a 0%, #020617 52%, #0a0f1a 100%);
}

.tc-bg-grid {
  position: absolute;
  inset: 0;
  opacity: 0.4;
  background-image:
    linear-gradient(rgba(148, 163, 184, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.055) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse 75% 65% at 50% 38%, black, transparent);
}

.tc-bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(88px);
  opacity: 0.42;
  pointer-events: none;
}

.tc-bg-orb-a {
  width: min(400px, 52vw);
  height: min(400px, 52vw);
  top: -12%;
  left: -8%;
  background: rgba(34, 211, 238, 0.18);
  opacity: 0.35;
}

.tc-bg-orb-b {
  width: min(340px, 48vw);
  height: min(340px, 48vw);
  bottom: -10%;
  right: -6%;
  background: rgba(129, 140, 248, 0.16);
  opacity: 0.3;
}

@keyframes tc-drift {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(20px, -18px) scale(1.04);
  }
}

.tc-shell {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 1024px;
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(340px, 1.12fr);
  gap: 0;
  border-radius: 22px;
  overflow: hidden;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.05),
    0 28px 90px rgba(0, 0, 0, 0.5);
}

.tc-hero {
  padding: clamp(24px, 4.5vw, 44px);
  background: linear-gradient(185deg, rgba(15, 23, 42, 0.98) 0%, rgba(12, 18, 32, 0.92) 100%);
  border-right: 1px solid var(--tc-border);
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 18px;
}

.tc-hero-mark {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 14px;
}

.tc-hero-logo-wrap {
  flex-shrink: 0;
  width: 72px;
  height: 72px;
  overflow: hidden;
  border-radius: 10px;
  filter: drop-shadow(0 0 14px rgba(34, 211, 238, 0.14));
}

.tc-hero-logo {
  display: block;
  width: 72px;
  height: auto;
  margin-top: -2px;
  object-fit: contain;
  object-position: top center;
}

.tc-hero-copy {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.tc-hero-title {
  font-size: clamp(1.35rem, 2.6vw, 1.65rem);
  font-weight: 700;
  letter-spacing: -0.02em;
  color: #f8fafc;
  line-height: 1.2;
  margin: 0;
}

.tc-hero-tag {
  margin: 0;
  font-size: 12px;
  color: var(--tc-muted);
  font-weight: 500;
  line-height: 1.4;
}

.tc-hero-lead {
  font-size: 13px;
  line-height: 1.65;
  color: #cbd5e1;
  max-width: 36ch;
}

.tc-hero-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 9px;
  font-size: 12px;
  color: var(--tc-muted);
}

.tc-hero-list li {
  display: flex;
  align-items: center;
  gap: 9px;
}

.tc-hero-list li::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--tc-accent);
  box-shadow: 0 0 10px rgba(34, 211, 238, 0.55);
  flex-shrink: 0;
}

.tc-hero-foot {
  margin-top: auto;
  padding-top: 14px;
  font-size: 11px;
  color: #64748b;
  letter-spacing: 0.03em;
}

.tc-card {
  background: var(--tc-surface);
  backdrop-filter: blur(22px);
  -webkit-backdrop-filter: blur(22px);
  padding: clamp(22px, 3.8vw, 34px);
  display: flex;
  flex-direction: column;
  min-height: min(620px, 90vh);
}

.tc-seg {
  display: flex;
  padding: 3px;
  border-radius: 11px;
  background: rgba(2, 6, 23, 0.5);
  border: 1px solid var(--tc-border);
  margin-bottom: 22px;
  flex-shrink: 0;
}

.tc-seg-btn {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--tc-muted);
  font-size: 13px;
  font-weight: 600;
  padding: 9px 14px;
  border-radius: 8px;
  cursor: pointer;
  transition:
    color 0.2s,
    background 0.2s,
    box-shadow 0.2s;
}

.tc-seg-btn:hover {
  color: #f1f5f9;
}

.tc-seg-btn.active {
  background: rgba(34, 211, 238, 0.11);
  color: var(--tc-accent);
  box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.22);
}

.form-section.tc-form-section {
  flex: 1;
  padding: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 0;
}

/* 表单组 */
.form-group {
  margin-bottom: 24px;
  position: relative;
}

/* 输入容器（深色表单） */
.input-container {
  position: relative;
  display: flex;
  align-items: center;
  background: rgba(2, 6, 23, 0.45);
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 12px;
  transition:
    border-color 0.2s,
    box-shadow 0.2s,
    background 0.2s;
  overflow: hidden;
}

.input-container:focus-within {
  border-color: rgba(34, 211, 238, 0.55);
  box-shadow: 0 0 0 3px rgba(34, 211, 238, 0.12);
  background: rgba(15, 23, 42, 0.55);
}

.input-icon {
  padding: 0 16px;
  color: #64748b;
  font-size: 18px;
  transition: color 0.2s ease;
}

.input-container:focus-within .input-icon {
  color: #22d3ee;
}

.form-input {
  flex: 1;
  height: 52px;
  padding: 0 14px;
  border: none;
  background: transparent;
  font-size: 15px;
  font-weight: 500;
  color: #f1f5f9;
  outline: none;
  transition: color 0.2s ease;
}

.form-input::placeholder {
  color: #64748b;
  font-weight: 400;
}

/* 密码输入包装器 */
.password-input-wrapper {
  flex: 1;
  position: relative;
}

.password-input {
  width: 100%;
  height: 52px;
  padding: 0 50px 0 0;
  border: none;
  background: transparent;
  font-size: 15px;
  font-weight: 500;
  color: #f1f5f9;
  outline: none;
}

.password-toggle {
  position: absolute;
  right: 14px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  font-size: 18px;
  cursor: pointer;
  color: #64748b;
  outline: none;
  transition: all 0.2s ease;
  padding: 8px;
  border-radius: 6px;
}

.password-toggle:hover {
  color: #22d3ee;
  background: rgba(34, 211, 238, 0.1);
}

/* 验证提示 */
.validate-tip {
  font-size: 12px;
  height: 20px;
  line-height: 20px;
  margin-top: 8px;
  padding-left: 8px;
  visibility: hidden;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: all 0.3s ease;
}

.validate-tip.show {
  visibility: visible;
  animation: slideIn 0.3s ease;
}

.validate-tip.error {
  color: #f87171;
}

.validate-tip.success {
  color: #34d399;
}

/* 表单底部 */
.form-footer {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin: 12px 0 22px;
  font-size: 13px;
  gap: 12px;
}

/* 协议复选框 */
.agreement {
  margin: 0;
  flex: 1;
}

/* 忘记密码 */
.forgot-password-hint {
  margin: 0;
  line-height: 1.65;
  color: #606266;
  font-size: 14px;
}

.forgot-password {
  margin-left: 20px;
}

.forgot-link {
  color: #22d3ee;
  text-decoration: none;
  font-weight: 500;
  transition: color 0.2s ease;
  font-size: 13px;
  white-space: nowrap;
}

.forgot-link:hover {
  color: #67e8f9;
  text-decoration: underline;
}

.checkbox-container {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
  user-select: none;
  transition: all 0.3s ease;
}

.checkbox-container.checkbox-readonly {
  cursor: default;
}

.checkbox-container.checkbox-readonly:hover {
  transform: none;
}

.checkbox-container.checkbox-readonly .label-text {
  color: #64748b;
}

.agreement-prereq {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.45;
  color: #fbbf24;
  padding-left: 4px;
}

.checkbox-container:hover {
  transform: translateX(4px);
}

.checkbox-container input {
  position: absolute;
  opacity: 0;
  cursor: pointer;
  height: 0;
  width: 0;
}

.checkmark {
  position: relative;
  top: 2px;
  height: 20px;
  width: 20px;
  background-color: rgba(248, 250, 252, 0.04);
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 6px;
  transition: all 0.2s ease;
}

.checkbox-container:hover input ~ .checkmark {
  border-color: rgba(34, 211, 238, 0.45);
  background-color: rgba(34, 211, 238, 0.06);
}

.checkbox-container.checkbox-readonly:hover input ~ .checkmark,
.checkbox-container.checkbox-readonly input ~ .checkmark {
  border-color: rgba(148, 163, 184, 0.25);
  background-color: rgba(15, 23, 42, 0.4);
}

.checkbox-container input:checked ~ .checkmark {
  background: #0d9488;
  border-color: transparent;
}

.checkmark:after {
  content: "";
  position: absolute;
  display: none;
  left: 6px;
  top: 2px;
  width: 6px;
  height: 12px;
  border: solid white;
  border-width: 0 2px 2px 0;
  transform: rotate(45deg);
}

.checkbox-container input:checked ~ .checkmark:after {
  display: block;
}

.label-text {
  color: #94a3b8;
  line-height: 1.45;
  font-size: 13px;
}

.agreement-link {
  color: #22d3ee;
  text-decoration: none;
  font-weight: 600;
  transition: color 0.2s ease;
}

.agreement-link:hover {
  color: #67e8f9;
  text-decoration: underline;
}

/* 按钮样式 */
.btn {
  width: 100%;
  height: 50px;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.02em;
  cursor: pointer;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    opacity 0.2s ease;
  outline: none;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  position: relative;
  overflow: hidden;
}

.login-btn {
  background: #0d9488;
  color: #f8fafc;
  box-shadow: 0 4px 14px rgba(13, 148, 136, 0.28);
}

.login-btn:hover:not(:disabled) {
  transform: none;
  background: #0f766e;
  box-shadow: 0 6px 18px rgba(13, 148, 136, 0.32);
}

.login-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.register-btn {
  background: #334155;
  color: #f8fafc;
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.2);
}

.register-btn:hover:not(:disabled) {
  transform: none;
  background: #1e293b;
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.28);
}

.register-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.btn-icon {
  font-size: 18px;
  transition: transform 0.3s ease;
}

.btn:hover .btn-icon {
  transform: translateX(3px);
}

/* 密码强度条 */
.password-strength {
  display: flex;
  gap: 4px;
  margin-top: 8px;
  height: 6px;
  visibility: hidden;
  border-radius: 3px;
  overflow: hidden;
}

.password-strength.show {
  visibility: visible;
  animation: slideIn 0.3s ease;
}

.strength-bar {
  flex: 1;
  border-radius: 3px;
  background: rgba(51, 65, 85, 0.65);
  transition: all 0.3s ease;
}

.strength-bar.weak {
  background: #ef4444;
}

.strength-bar.medium {
  background: #f59e0b;
}

.strength-bar.strong {
  background: #10b981;
}

@keyframes slideIn {
  from {
    opacity: 0;
    transform: translateX(-20px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}

/* 响应式 */
@media (max-width: 900px) {
  .tc-shell {
    grid-template-columns: 1fr;
    max-width: 460px;
  }

  .tc-hero {
    border-right: none;
    border-bottom: 1px solid var(--tc-border);
    padding: 20px;
  }

  .tc-hero-list {
    display: none;
  }

  .tc-hero-foot {
    margin-top: 4px;
  }

  .tc-card {
    min-height: 0;
  }
}

@media (max-width: 768px) {
  .login-container {
    padding: 14px;
  }

  .tc-shell {
    border-radius: 18px;
  }

  .form-footer {
    flex-direction: column;
    align-items: flex-start;
  }

  .forgot-password {
    margin-left: 0;
  }

  .form-group {
    margin-bottom: 18px;
  }

  .forgot-password-dialog {
    width: 90% !important;
    max-width: 400px;
  }

  .forgot-password-dialog .el-dialog__body {
    padding: 24px 20px;
  }

  .forgot-password-dialog .el-dialog__header {
    padding: 16px 20px;
  }

  .dialog-footer {
    padding: 0 20px 20px;
  }
}

@media (max-width: 480px) {
  .login-container {
    padding: 10px;
  }

  .tc-shell {
    border-radius: 14px;
    box-shadow: 0 20px 50px rgba(0, 0, 0, 0.45);
  }

  .tc-hero-mark {
    flex-wrap: wrap;
  }

  .tc-hero-logo-wrap {
    width: 60px;
    height: 60px;
  }

  .tc-hero-logo {
    width: 60px;
  }

  .tc-seg-btn {
    padding: 8px;
    font-size: 12px;
  }

  .form-input,
  .password-input {
    height: 48px;
    font-size: 14px;
  }

  .btn {
    height: 46px;
    font-size: 14px;
  }

  .validate-tip {
    font-size: 11px;
    margin-top: 6px;
  }

  .password-strength {
    margin-top: 6px;
    height: 4px;
  }

  .forgot-password-dialog {
    width: 95% !important;
    max-width: 350px;
  }

  .forgot-password-dialog .el-dialog__body {
    padding: 20px 16px;
  }

  .forgot-password-dialog .el-dialog__header {
    padding: 14px 16px;
  }

  .forgot-password-dialog .el-dialog__title {
    font-size: 16px;
  }

  .dialog-footer {
    padding: 0 16px 16px;
    flex-direction: column;
    gap: 8px;
  }

  .dialog-footer .btn {
    width: 100%;
  }
}

/* 忘记密码弹窗 */
.forgot-password-dialog {
  border-radius: 12px;
  overflow: hidden;
}

.forgot-password-dialog .el-dialog__header {
  background: #f8fafc;
  color: #0f172a;
  padding: 14px 18px;
  margin: 0;
  border-bottom: 1px solid #e2e8f0;
}

.forgot-password-dialog .el-dialog__title {
  color: #0f172a;
  font-size: 15px;
  font-weight: 600;
}

.forgot-password-dialog .el-dialog__headerbtn .el-dialog__close {
  color: #64748b;
  font-size: 18px;
}

.forgot-password-dialog .el-dialog__body {
  padding: 30px 24px;
}

.forgot-password-form .form-group {
  margin-bottom: 20px;
}

.forgot-password-dialog .input-container {
  background: #f8fafc;
  border: 2px solid #e2e8f0;
}

.forgot-password-dialog .input-container:focus-within {
  border-color: #22d3ee;
  box-shadow: 0 0 0 4px rgba(34, 211, 238, 0.12);
  background: #fff;
}

.forgot-password-dialog .input-icon {
  color: #94a3b8;
}

.forgot-password-dialog .input-container:focus-within .input-icon {
  color: #0e7490;
}

.forgot-password-dialog .form-input,
.forgot-password-dialog .password-input {
  color: #1e293b;
}

.forgot-password-dialog .password-toggle:hover {
  color: #0891b2;
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 0 24px 24px;
}

.cancel-btn {
  background: #f1f5f9;
  color: #64748b;
  border: 1px solid #e2e8f0;
}

.cancel-btn:hover {
  background: #e2e8f0;
  transform: none;
}

.reset-btn {
  background: #0d9488;
  color: #f8fafc;
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(13, 148, 136, 0.22);
}

.reset-btn:hover:not(:disabled) {
  transform: none;
  background: #0f766e;
  box-shadow: 0 6px 16px rgba(13, 148, 136, 0.28);
}

.reset-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}
</style>