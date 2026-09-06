<script setup lang="ts">
import type { QualityOverviewMetric, QualityOverviewRow } from '../quality-overview';

defineProps<{
  title: string;
  description: string;
  metrics: QualityOverviewMetric[];
  rows: QualityOverviewRow[];
  warning: string;
  safetyText: string;
  exportLabel: string;
  primaryLabel: string;
  detailLabel: string;
  loading?: boolean;
  error?: string;
}>();

defineEmits<{ export: []; primary: []; detail: []; retry: [] }>();
</script>

<template>
  <section data-page-root class="content vue-native-page quality-overview-page">
    <div class="page-head">
      <div class="page-title"><h1>{{ title }}</h1><p>{{ description }}</p></div>
      <div class="head-actions"><button class="btn" type="button" @click="$emit('export')">{{ exportLabel }}</button><button class="btn primary" type="button" @click="$emit('primary')">{{ primaryLabel }}</button></div>
    </div>

    <div v-if="error" class="quality-overview-state error" role="alert"><b>数据读取失败</b><span>{{ error }}</span><button class="btn sm" type="button" @click="$emit('retry')">重新加载</button></div>
    <div v-else-if="loading" class="quality-overview-state" role="status">正在读取实时质量工作队列…</div>
    <template v-else>
      <section class="quality-overview-metrics" aria-label="质量指标">
        <article v-for="metric in metrics" :key="metric.label"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong><small>{{ metric.hint }}</small></article>
      </section>

      <section class="quality-overview-layout">
        <div class="quality-workbench card">
          <header><b>当前工作队列</b><span>实时业务数据</span></header>
          <div class="quality-table-wrap"><table><thead><tr><th>对象</th><th>关键资料</th><th>进度/来源</th><th>状态</th></tr></thead><tbody><tr v-for="row in rows" :key="row.object"><td><b>{{ row.object }}</b></td><td>{{ row.keyData }}</td><td>{{ row.progress }}</td><td><span class="status" :class="row.tone">{{ row.status }}</span></td></tr></tbody></table></div>
        </div>

        <aside class="quality-safety card">
          <header><b>待核验事项</b></header>
          <div class="quality-warning"><b>{{ warning }}</b><p>{{ safetyText }}</p></div>
          <button class="btn primary quality-detail-button" type="button" @click="$emit('detail')">{{ detailLabel }}</button>
        </aside>
      </section>
    </template>
  </section>
</template>

<style scoped>
.quality-overview-page{min-width:0}.quality-overview-state{display:flex;align-items:center;gap:12px;padding:18px;border:1px solid var(--line);border-radius:10px;background:#fff;color:var(--muted)}.quality-overview-state.error{border-color:#f1b7b7;background:#fff7f7;color:#b4232f}.quality-overview-state .btn{margin-left:auto}.quality-overview-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-bottom:14px}.quality-overview-metrics article{display:grid;gap:6px;min-height:100px;padding:15px 17px;border:1px solid var(--line);border-top:3px solid var(--blue);border-radius:11px;background:#fff;box-shadow:var(--shadow)}.quality-overview-metrics span{color:var(--muted);font-size:11px}.quality-overview-metrics strong{font-size:26px;line-height:1.1;color:var(--ink)}.quality-overview-metrics small{color:var(--muted-2);font-size:10px}.quality-overview-layout{display:grid;grid-template-columns:minmax(0,1fr) 330px;gap:14px}.quality-workbench,.quality-safety{overflow:hidden}.quality-workbench>header,.quality-safety>header{display:flex;align-items:center;justify-content:space-between;padding:13px 15px;border-bottom:1px solid var(--line)}.quality-workbench>header span{padding:4px 8px;border-radius:12px;background:#f1f5f9;color:var(--muted);font-size:9px}.quality-table-wrap{overflow:auto}.quality-table-wrap table{width:100%;border-collapse:collapse}.quality-table-wrap th,.quality-table-wrap td{padding:12px 14px;border-bottom:1px solid #edf1f4;text-align:left;font-size:11px}.quality-table-wrap th{background:#f8fafc;color:var(--muted);font-size:9px}.status{display:inline-flex;padding:4px 8px;border-radius:10px;font-size:9px;font-weight:700}.status.red{background:#fff0f0;color:#b4232f}.status.yellow{background:#fff7df;color:#946200}.status.green{background:#eaf8ef;color:#18794e}.status.blue{background:#eaf3ff;color:#1769aa}.quality-workflow{display:flex;align-items:center;justify-content:space-between;gap:7px;padding:16px}.quality-workflow article{display:flex;align-items:center;gap:7px;min-width:0}.quality-workflow article span{display:grid;place-items:center;flex:0 0 24px;width:24px;height:24px;border-radius:50%;background:#eaf3ff;color:var(--blue);font-size:10px;font-weight:800}.quality-workflow article b{font-size:9px;white-space:nowrap}.quality-workflow i{color:#9aaabd;font-size:19px;font-style:normal}.quality-warning{padding:14px;border-bottom:1px solid var(--line);background:#fff8e8}.quality-warning>b{color:#8a5a00;font-size:11px}.quality-warning p{margin:7px 0 0;color:#6e6247;font-size:10px;line-height:1.6}.quality-safety dl{display:grid;gap:10px;margin:0;padding:14px}.quality-safety dl div{display:flex;justify-content:space-between;gap:12px}.quality-safety dt{color:var(--muted);font-size:10px}.quality-safety dd{margin:0;text-align:right;font-size:10px;font-weight:700}.quality-detail-button{width:calc(100% - 28px);margin:0 14px 14px}.head-actions .btn{text-decoration:none}@media(max-width:1050px){.quality-overview-layout{grid-template-columns:1fr}.quality-safety{display:grid;grid-template-columns:1fr 1fr}.quality-safety>header{grid-column:1/-1}.quality-detail-button{align-self:end}}@media(max-width:820px){.quality-overview-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.quality-workflow{overflow:auto;justify-content:flex-start}.quality-workflow article{min-width:126px}}@media(max-width:600px){.page-head{height:auto;align-items:stretch;flex-direction:column;padding:10px 0}.head-actions{margin-left:0;display:flex;flex-wrap:wrap}.head-actions .btn{flex:1 1 140px}.quality-overview-metrics{grid-template-columns:1fr 1fr}.quality-overview-layout{grid-template-columns:1fr}.quality-safety{display:block}.quality-table-wrap table{min-width:620px}}
</style>
