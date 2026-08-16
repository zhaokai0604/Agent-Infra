import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/ops-console-theme.css'
import './styles/agent-workspace.css'
import './styles/assistant-prose.css'
import './styles/ops-page-layout.css'
import App from './App.vue'

const app = createApp(App)
app.use(ElementPlus)

// 全局样式和动画
app.config.globalProperties.$animate = {
  fadeIn: 'animate__fadeIn',
  fadeInUp: 'animate__fadeInUp',
  zoomIn: 'animate__zoomIn',
  slideInRight: 'animate__slideInRight'
}

// 挂载应用
app.mount('#app')
