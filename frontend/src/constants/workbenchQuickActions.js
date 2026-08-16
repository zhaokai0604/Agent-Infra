import {
  Coin,
  FolderOpened,
  TrendCharts,
  Document,
  Connection,
  Link,
  Monitor,
  Delete,
  DataAnalysis,
  Files,
  RefreshRight,
  Warning,
  Timer,
  Clock
} from '@element-plus/icons-vue'

/**
 * 助手快捷指令（技能页/扩展用）。
 * 原则：短指令能落到真实工具；演练类写清「预览」；砍掉口号式/重复项。
 */
export const WORKBENCH_QUICK_GROUPS = [
  {
    name: '巡检',
    items: [
      { label: '全面检查', icon: DataAnalysis, cmd: '帮我全面检查系统状态，发现问题并给出修复计划' },
      { label: '一键巡检', icon: RefreshRight, cmd: '一键巡检本机健康状态' }
    ]
  },
  {
    name: '观测',
    items: [
      { label: '磁盘检查', icon: Coin, cmd: '检查磁盘使用情况' },
      { label: '磁盘热点', icon: FolderOpened, cmd: '扫描临时目录磁盘占用热点' },
      { label: '系统负载', icon: TrendCharts, cmd: '查看系统负载' },
      { label: '进程资源', icon: Monitor, cmd: '查看占用资源最多的进程' },
      { label: '网络诊断', icon: Link, cmd: '检查网络连通性' }
    ]
  },
  {
    name: '诊断',
    items: [
      { label: '最近日志', icon: Document, cmd: '分析最近的系统日志' },
      { label: '端口监听', icon: Connection, cmd: '查看本机监听端口概况' }
    ]
  },
  {
    name: '处置',
    items: [
      { label: '临时清理预览', icon: Delete, cmd: '预览清理 7 天前的临时文件' },
      { label: '日志清理预览', icon: Files, cmd: '预览清理 30 天前的陈旧日志' },
      { label: '确认执行', icon: RefreshRight, cmd: '确认执行' },
      { label: '延时清理', icon: Timer, cmd: '30 分钟之后帮我清理临时文件' },
      { label: '延时巡检', icon: Clock, cmd: '20 分钟之后检查磁盘使用情况' }
    ]
  }
]

export const PATROL_QUICK_ITEM = {
  label: '处理巡检待办',
  icon: Warning,
  action: 'patrol'
}
