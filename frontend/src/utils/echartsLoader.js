/**
 * 按需加载 ECharts，避免首屏把所有图表页打进主包。
 */
let echartsPromise

export function loadEcharts() {
  if (!echartsPromise) {
    echartsPromise = import('echarts')
  }
  return echartsPromise
}
