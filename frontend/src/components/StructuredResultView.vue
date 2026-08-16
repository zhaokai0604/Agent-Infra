<template>
  <div class="structured-result-view" :class="{ 'is-nested': depth > 0 }">
    <el-empty v-if="model.kind === 'empty'" description="暂无可展示数据" :image-size="56" />

    <p v-else-if="model.kind === 'text'" class="sr-text">{{ model.text }}</p>

    <div v-else-if="model.kind === 'tags'" class="sr-section">
      <div v-if="model.title" class="sr-section-title">{{ model.title }}</div>
      <div class="sr-tags">
        <el-tag v-for="(tag, i) in model.items" :key="i" size="small" type="info" effect="plain">
          {{ tag }}
        </el-tag>
      </div>
    </div>

    <div v-else-if="model.kind === 'table'" class="sr-section">
      <div v-if="model.title" class="sr-section-title">{{ model.title }}</div>
      <el-table :data="model.rows" size="small" stripe border max-height="280" class="sr-table">
        <el-table-column
          v-for="col in model.columns"
          :key="col.prop"
          :prop="col.prop"
          :label="col.label"
          :min-width="col.minWidth"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <el-tag
              v-if="col.prop === 'severity' || col.prop === 'status'"
              size="small"
              :type="tagTypeForField(col.prop, row[col.prop])"
            >
              {{ formatTableCell(col.prop, row[col.prop]) }}
            </el-tag>
            <span v-else>{{ formatTableCell(col.prop, row[col.prop]) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-else-if="model.kind === 'cards'" class="sr-section">
      <div v-if="model.title" class="sr-section-title">{{ model.title }}</div>
      <div class="sr-card-grid">
        <el-card v-for="(card, i) in model.cards" :key="i" shadow="never" class="sr-mini-card">
          <template #header v-if="card.title">
            <span class="sr-card-title">{{ card.title }}</span>
          </template>
          <StructuredResultView :data="card.view" :depth="depth + 1" />
        </el-card>
      </div>
    </div>

    <template v-else-if="model.kind === 'composite'">
      <div v-if="model.metrics?.length" class="sr-metrics">
        <el-card
          v-for="m in model.metrics"
          :key="m.key"
          shadow="never"
          class="sr-metric-card"
          body-style="padding: 12px 14px"
        >
          <div class="sr-metric-label">{{ m.label }}</div>
          <el-progress
            :percentage="m.percent"
            :status="m.status"
            :stroke-width="10"
            :format="() => (m.hint ? m.hint : `${m.percent}%`)"
          />
        </el-card>
      </div>

      <el-descriptions
        v-if="model.fields?.length"
        :column="descriptionColumn"
        border
        size="small"
        class="sr-fields"
      >
        <el-descriptions-item v-for="f in model.fields" :key="f.key" :label="f.label">
          <el-tag v-if="f.tagType" :type="f.tagType" size="small">{{ f.value }}</el-tag>
          <span v-else>{{ f.value }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-for="(sec, idx) in model.sections" :key="idx" class="sr-section">
        <div v-if="sec.title" class="sr-section-title">{{ sec.title }}</div>
        <el-card v-if="sec.kind === 'nested'" shadow="never" class="sr-nested-card" body-style="padding: 10px 12px">
          <StructuredResultView :view-model="sec.view" :depth="depth + 1" />
        </el-card>
        <StructuredResultView v-else :view-model="sec" :depth="depth + 1" />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { buildStructuredViewModel, formatTableCell } from '../utils/structuredDataView'

const props = defineProps({
  data: { type: null, default: null },
  depth: { type: Number, default: 0 },
  viewModel: { type: Object, default: null }
})

const model = computed(() => props.viewModel || buildStructuredViewModel(props.data, props.depth))
const descriptionColumn = computed(() => (props.depth > 0 ? 1 : 2))

function tagTypeForField(key, val) {
  const k = String(key).toLowerCase()
  if (typeof val === 'boolean') return val ? 'success' : 'info'
  if (k === 'success' || k === 'executionok' || k === 'passed') {
    return val === true || val === 'true' ? 'success' : 'danger'
  }
  if (k.includes('error') || k.includes('fail') || k === 'severity') return 'danger'
  if (k.includes('warn') || k === 'risklevel') return 'warning'
  if (k.includes('status') || k === 'mode') return 'info'
  return undefined
}
</script>

<script>
export default { name: 'StructuredResultView' }
</script>

<style scoped>
.structured-result-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.structured-result-view.is-nested {
  gap: 8px;
}
.sr-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-word;
}
.sr-section-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.sr-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
}
.sr-metric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.sr-metric-card,
.sr-nested-card,
.sr-mini-card {
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-blank);
}
.sr-fields {
  width: 100%;
}
.sr-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.sr-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px;
}
.sr-card-title {
  font-size: 12px;
  font-weight: 600;
}
.sr-table {
  width: 100%;
}
</style>
