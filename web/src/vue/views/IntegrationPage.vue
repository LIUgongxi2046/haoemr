<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed } from 'vue';
import type { ConfigurationItemWire } from '../../generated/contracts';
import { issueConfigurationLease, listConfigurations } from '../../api/config';
import { getOutboxReconciliation, issueIntegrationLease, listIntegrationReconciliations } from '../../api/integration';
import { toClinicalIssue } from '../clinical-error';

const configLeaseQuery = useQuery({
  queryKey: ['integration', 'config-lease'], queryFn: issueConfigurationLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const connectorsQuery = useQuery({
  queryKey: ['integration', 'connectors'],
  queryFn: () => listConfigurations(configLeaseQuery.data.value!, 'INTEGRATION_CONNECTOR'),
  enabled: () => Boolean(configLeaseQuery.data.value), retry: false,
});
const incidentsQuery = useQuery({
  queryKey: ['integration', 'incidents'],
  queryFn: () => listConfigurations(configLeaseQuery.data.value!, 'INTEGRATION_INCIDENT'),
  enabled: () => Boolean(configLeaseQuery.data.value), retry: false,
});
const reconciliationLeaseQuery = useQuery({
  queryKey: ['integration', 'reconciliation-lease'],
  queryFn: issueIntegrationLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const reconciliationsQuery = useQuery({
  queryKey: ['integration', 'reconciliations'],
  queryFn: () => listIntegrationReconciliations(reconciliationLeaseQuery.data.value!),
  enabled: () => Boolean(reconciliationLeaseQuery.data.value), retry: false,
});
const outboxQuery = useQuery({
  queryKey: ['integration', 'outbox-reconciliation'],
  queryFn: () => getOutboxReconciliation(reconciliationLeaseQuery.data.value!),
  enabled: () => Boolean(reconciliationLeaseQuery.data.value), retry: false, staleTime: 0, gcTime: 0,
});
const issue = computed(() => {
  const error = configLeaseQuery.error.value
    ?? connectorsQuery.error.value ?? incidentsQuery.error.value
    ?? reconciliationLeaseQuery.error.value ?? reconciliationsQuery.error.value ?? outboxQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});

const connectors = computed(() => (connectorsQuery.data.value ?? []).filter((item) => item.status === 'ACTIVE'));
const incidents = computed(() => (incidentsQuery.data.value ?? []).filter((item) => item.status === 'ACTIVE'));
function text(item: ConfigurationItemWire, key: string, fallback = '—') {
  const value = item.payload?.[key];
  return typeof value === 'string' && value.trim() ? value : fallback;
}
function number(item: ConfigurationItemWire, key: string) {
  const value = Number(item.payload?.[key] ?? 0);
  return Number.isFinite(value) ? value : 0;
}
function systemTypeLabel(value: string) {
  return ({ LIS: '检验 LIS', PACS: '影像 PACS', HIS: '费用医保 HIS', CA: '电子签名 CA', HIE: '区域平台', PHARMACY: '药品管理', BLOOD_BANK: '临床输血', PATHOLOGY: '病理系统', ANESTHESIA: '麻醉手术', IOMT: '医疗设备' } as Record<string, string>)[value] ?? value;
}
function statusLabel(item: ConfigurationItemWire) {
  return ({ HEALTHY: '正常', DEGRADED: '降级', BACKLOG: '积压', OFFLINE: '离线' } as Record<string, string>)[text(item, 'operational_status')] ?? text(item, 'operational_status');
}
function statusTone(item: ConfigurationItemWire) {
  return ({ HEALTHY: 'green', DEGRADED: 'amber', BACKLOG: 'red', OFFLINE: 'red' } as Record<string, string>)[text(item, 'operational_status')] ?? 'blue';
}
const messageVolume = computed(() => (reconciliationsQuery.data.value ?? [])
  .reduce((sum, item) => sum + item.sent_count, 0));
const errorCount = computed(() => (reconciliationsQuery.data.value ?? [])
  .reduce((sum, item) => sum + item.error_count, 0));
const pendingCount = computed(() => (reconciliationsQuery.data.value ?? [])
  .reduce((sum, item) => sum + item.pending_count, 0));
const blockingCount = computed(() => (reconciliationsQuery.data.value ?? [])
  .filter((item) => item.status === 'OPEN').length);
const successRate = computed(() => messageVolume.value > 0
  ? ((messageVolume.value - errorCount.value) / messageVolume.value * 100).toFixed(2) : '—');
const formatInteger = (value: number) => new Intl.NumberFormat('zh-CN').format(value);
const outbox = computed(() => outboxQuery.data.value);
const outboxDeliveryState = computed(() => outbox.value?.external_delivery_configured
  ? (outbox.value.scheduled_dispatch_enabled ? '已启用外部投递' : '外部通道已配置，调度未启用')
  : '医院外部通道未配置');
const outboxDeliveryNotice = computed(() => outbox.value?.external_delivery_configured
  ? '仅有外部通道回执的事件才计为“外部已接受”；本地投影不作为送达证据。'
  : '为避免误报送达，新的事件会保持“待投递”；历史已关闭记录若没有外部回执，会单列为未证实。');
const formatAge = (seconds: number | undefined) => {
  if (!seconds) return '—';
  if (seconds < 60) return `${seconds} 秒`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟`;
  return `${Math.floor(seconds / 3600)} 小时`;
};
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-head">
      <div class="page-title"><h1>外部系统集成与互操作中心</h1><p>集中查看外部系统连接、消息积压和异常处理。</p></div>
      <div class="head-actions"><RouterLink class="btn" to="/integration-mapping">集成拓扑</RouterLink><RouterLink class="btn" :to="{ path: '/integration-connectors', query: { action: 'create' } }">新建连接器</RouterLink><RouterLink class="btn primary" to="/integration-messages">采集消息</RouterLink></div>
    </div>

    <div v-if="configLeaseQuery.isPending.value || connectorsQuery.isPending.value || incidentsQuery.isPending.value || reconciliationLeaseQuery.isPending.value || reconciliationsQuery.isPending.value || outboxQuery.isPending.value" class="card"><div class="card-body">正在读取集成接口与运行台账…</div></div>
    <div v-else-if="issue" class="card"><div class="card-body">加载失败：{{ issue.code }} {{ issue.message }}</div></div>

    <template v-else>
      <section class="metric-grid integration-metrics" aria-label="集成运行指标">
        <article class="metric"><div class="name">生产连接器</div><div class="value">{{ connectors.length }}</div><div class="trend">来自已发布连接器目录</div></article>
        <article class="metric"><div class="name">对账窗口消息</div><div class="value">{{ formatInteger(messageVolume) }}</div><div class="trend">送达率 {{ successRate }}%</div></article>
        <article class="metric"><div class="name">失败 / 死信</div><div class="value metric-danger">{{ formatInteger(errorCount) }}</div><div class="trend">开放对账窗口 {{ blockingCount }}</div></article>
        <article class="metric"><div class="name">待对账</div><div class="value">{{ pendingCount }}</div><div class="trend">开放差异工单 {{ incidents.length }}</div></article>
      </section>


      <div class="grid integration-layout">
        <section class="card scroll-card">
          <div class="card-head">系统与运行状态 <span class="sub">连接器运行台账</span></div>
          <div v-if="connectors.length === 0" class="card-body">暂无已发布连接器。</div>
          <div v-else class="card-body">
            <div v-for="item in connectors" :key="item.config_id" class="extension-card integration-system-row">
              <div class="integration-system-summary">
                <b>{{ item.display_name }}</b>
                <span class="status blue">{{ systemTypeLabel(text(item, 'system_type')) }}</span>
                <span>{{ text(item, 'protocol') }}</span>
                <span class="status" :class="statusTone(item)">{{ statusLabel(item) }}</span>
                <span>{{ formatInteger(number(item, 'message_volume_24h')) }} / 24h</span>
                <span>{{ number(item, 'error_count_24h') }} 异常</span>
                <RouterLink class="btn sm integration-test-button" to="/mock-interfaces/integration-connectors">前往模拟接口测试</RouterLink>
              </div>
              <p>{{ text(item, 'description') }}</p>
            </div>
          </div>
        </section>

        <aside class="card scroll-card">
          <div class="card-head">互操作与消息对账</div>
          <div class="card-body">
            <div v-if="incidents.length === 0" class="admin-empty">当前没有开放差异工单。</div>
            <div v-for="incident in incidents.slice(0, 4)" :key="incident.config_id" class="notice" :class="text(incident, 'result') === 'RECOVERED' ? 'info' : 'hard'">
              <div class="notice-title">{{ incident.display_name }}</div>{{ text(incident, 'clinical_impact') }}
            </div>
            <div v-for="connector in connectors" :key="`status-${connector.config_id}`" class="folder-row">{{ connector.display_name }}<span class="status" :class="statusTone(connector)">{{ statusLabel(connector) }}</span></div>
            <RouterLink class="btn" style="width:100%;margin-top:12px" to="/integration-messages">查看异常消息</RouterLink>
          </div>
        </aside>
      </div>

      <section v-if="outbox" class="card outbox-reconciliation" aria-label="医院外部投递状态">
        <div class="card-head">医院外部投递状态 <span class="status" :class="outbox.external_delivery_configured ? 'green' : 'amber'">{{ outboxDeliveryState }}</span></div>
        <div class="card-body">
          <p class="outbox-explainer">{{ outboxDeliveryNotice }}</p>
          <div class="outbox-metrics">
            <div><span>待投递</span><b>{{ formatInteger(outbox.pending) }}</b></div>
            <div><span>投递中</span><b>{{ formatInteger(outbox.in_flight) }}</b></div>
            <div><span>外部通道已接受</span><b>{{ formatInteger(outbox.external_delivery_accepted) }}</b></div>
            <div><span>已关闭但外部证据缺失</span><b :class="{ danger: outbox.closed_without_external_evidence > 0 }">{{ formatInteger(outbox.closed_without_external_evidence) }}</b></div>
            <div><span>死信</span><b :class="{ danger: outbox.dead_letter > 0 }">{{ formatInteger(outbox.dead_letter) }}</b></div>
            <div><span>最早待投递</span><b>{{ formatAge(outbox.oldest_pending_seconds) }}</b></div>
          </div>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.mock-payload { margin: 8px 0 0; padding: 10px; max-height: 260px; overflow: auto; color: #26384d; border: 1px solid var(--line); border-radius: 8px; background: #f8fafc; font-size: 11px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; }
.integration-layout { grid-template-columns: minmax(0, 1fr) 330px; align-items: start; }
.integration-layout > .scroll-card { height: auto; max-height: none; overflow: visible; }
.integration-metrics { margin-bottom: 14px; }
.metric-danger { color: var(--red); }
.head-actions { gap: 10px; }
.integration-system-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 10px; }
.integration-system-summary > span:not(.status) { color: var(--muted); font-size: 10px; }
.integration-test-button { margin-left: auto; }
.outbox-reconciliation { margin-top: 14px; }
.outbox-explainer { margin: 0 0 12px; color: var(--muted); line-height: 1.65; }
.outbox-metrics { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.outbox-metrics > div { min-height: 62px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-soft, #f8fafc); }
.outbox-metrics span { display: block; color: var(--muted); font-size: 11px; }
.outbox-metrics b { display: block; margin-top: 5px; font-size: 17px; color: var(--ink); }
.outbox-metrics b.danger { color: var(--red); }
@media (max-width: 960px) { .integration-layout { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 960px) { .outbox-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 700px) {
  .page-head { height: auto; min-height: 0; flex-direction: column; align-items: stretch; gap: 10px; margin-bottom: 14px; }
  .head-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-left: 0; }
  .head-actions .btn { flex: 1 1 140px; width: auto; min-height: 36px; text-align: center; }
  .integration-test-button { margin-left: 0; width: 100%; }
  .outbox-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
