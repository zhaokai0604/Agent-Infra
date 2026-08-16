<template>
  <!--
    Element Plus ElMenu / ElMenuItem 在动态分组与 collapse 下卸载时，若 ref 解析与 vnode 拆除顺序重叠，
    易触发 getComponentPublicInstance(null).exposed。此处：稳定 key、shallowRef + 卸载清空、关闭折叠动画。
  -->
  <el-menu
    ref="menuRef"
    :key="structureKey"
    mode="vertical"
    :collapse="collapse"
    :collapse-transition="false"
    :default-active="activeTab"
    background-color="transparent"
    text-color="rgba(226,232,240,0.88)"
    active-text-color="#f0fdfa"
    class="shell-nav-menu"
    style="height: calc(100vh - 132px); border-right: none;"
    @select="onMenuSelect"
  >
    <el-menu-item-group
      v-for="(group, gi) in groups"
      :key="groupKey(group, gi)"
      :title="collapse ? '' : group.title"
    >
      <el-menu-item
        v-for="item in group.items"
        :key="itemKey(item)"
        :index="item.key"
      >
        <el-icon><component :is="item.icon" :key="'icon-' + item.key" /></el-icon>
        <span v-if="!collapse">{{ item.label }}</span>
      </el-menu-item>
    </el-menu-item-group>
  </el-menu>
</template>

<script setup>
import { shallowRef, watch, nextTick, onBeforeUnmount } from 'vue'

const props = defineProps({
  groups: {
    type: Array,
    required: true
  },
  activeTab: {
    type: String,
    required: true
  },
  collapse: {
    type: Boolean,
    default: false
  },
  structureKey: {
    type: String,
    required: true
  }
})

const emit = defineEmits(['select'])

const menuRef = shallowRef(null)

const groupKey = (group, gi) => `nav-grp-${gi}-${slug(group.title)}`
const itemKey = (item) => `nav-item-${item.key}`
const slug = (s) => String(s).replace(/\s+/g, '-').slice(0, 48)

const onMenuSelect = (index) => {
  emit('select', index)
}

watch(
  () => props.activeTab,
  (v) => {
    nextTick(() => {
      const menu = menuRef.value
      if (menu && typeof menu.updateActiveIndex === 'function') {
        menu.updateActiveIndex(v)
      }
    })
  }
)

watch(
  () => props.structureKey,
  () => {
    nextTick(() => {
      const menu = menuRef.value
      if (menu && typeof menu.updateActiveIndex === 'function') {
        menu.updateActiveIndex(props.activeTab)
      }
    })
  }
)

onBeforeUnmount(() => {
  menuRef.value = null
})
</script>

<style scoped>
.shell-nav-menu {
  --el-menu-hover-bg-color: var(--shell-nav-hover, rgba(255, 255, 255, 0.06));
  --el-menu-bg-color: transparent;
  padding: 4px 8px;
}

.shell-nav-menu :deep(.el-menu-item-group__title) {
  padding: 14px 12px 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--shell-muted);
}

.shell-nav-menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  margin: 2px 0;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  transition: background 0.15s ease, color 0.15s ease;
}

.shell-nav-menu :deep(.el-menu-item:hover) {
  transform: none;
  background: var(--shell-nav-hover) !important;
}

.shell-nav-menu :deep(.el-menu-item.is-active) {
  transform: none;
  background: var(--shell-nav-active) !important;
  color: #f0fdfa !important;
  box-shadow: inset 3px 0 0 var(--shell-accent);
}

.shell-nav-menu :deep(.el-menu-item .el-icon) {
  font-size: 17px;
  margin-right: 8px;
}
</style>
