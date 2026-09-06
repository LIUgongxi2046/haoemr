<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import type { ConfigurationItemWire, IntegrationMessageWire } from '../../generated/contracts';
import { issueConfigurationLease, listConfigurations } from '../../api/config';
import {
  collectIntegrationMessages,
  issueIntegrationLease,
  listIntegrationMessages,
  listIntegrationReconciliations,
  reconcileIntegrationMessage,
} from '../../api/integration';
import { toClinicalIssue } from '../clinical-error';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';

const statusLabels: Record<IntegrationMessageWire['message_status'], string> = {
  PENDING: '待对账', DELIVERED: '已送达', RECONCILED: '已对账', FAILED: '失败',
};
const statusTone: Record<IntegrationMessageWire['message_status'], string> = {
  PENDING: 'amber', DELIVERED: 'green', RECONCILED: 'green', FAILED: 'red',
};

const statusFilter = ref('');
const connectorFilter = ref('');
const collectOpen = ref(false);
const collectConnector = ref('');
const collectDirection = ref<'INBOUND' | 'OUTBOUND'>('OUTBOUND');
const collectScenario = ref<'SUCCESS' | 'DEGRADED' | 'UNAVAILABLE'>('SUCCESS');
const collectCount = ref(36);
const busy = ref('');
const notice = ref('');

const configLeaseQuery = useQuery({
  queryKey: ['integration-messages', 'config-lease'], queryFn: issueConfigurationLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const connectorsQuery = useQuery({
  queryKey: ['integration-messages', 'connectors'],
  queryFn: () => listConfigurations(configLeaseQuery.data.value!, 'INTEGRATION_CONNECTOR'),
  enabled: () => Boolean(configLeaseQuery.data.value), retry: false,
});
const leaseQuery = useQuery({
  queryKey: ['integration-messages', 'lease'], queryFn: issueIntegrationLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const messagesQuery = useQuery({
  queryKey: ['integration-messages', 'messages', connectorFilter, statusFilter],
  queryFn: () => listIntegrationMessages(leaseQuery.data.value!, {
    connectorCode: connectorFilter.value || undefined, status: statusFilter.value || undefined,
  }),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const reconciliationsQuery = useQuery({
  queryKey: ['integration-messages', 'reconciliations', connectorFilter],
  queryFn: () => listIntegrationReconciliations(leaseQuery.data.value!, connectorFilter.value || undefined),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});

const connectors = computed<ConfigurationItemWire[]>(() => (connectorsQuery.data.value ?? [])
  .filter((item) => item.status === 'ACTIVE'));
const messages = computed(() => messagesQuery.data.value ?? []);
const reconciliations = computed(() => reconciliationsQuery.data.value ?? []);
const pendingCount = computed(() => messages.value.filter((item) => item.message_status === 'PENDING').length);
const deliveredCount = computed(() => messages.value.filter((item) => item.message_status === 'DELIVERED').length);
const failedCount = computed(() => messages.value.filter((item) => item.message_status === 'FAILED').length);
const issue = computed(() => {
  const error = configLeaseQuery.error.value ?? connectorsQuery.error.value
    ?? leaseQuery.error.value ?? messagesQuery.error.value ?? reconciliationsQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});

function interfaceFor(code: string): string {
  const type = connectors.value.find((item) => item.config_key === code)?.payload?.system_type;
  return ({ LIS: 'LIS_RESULTS', PACS: 'PACS_IMAGES', HIS: 'HIS_INSURANCE', CA: 'CA_TIMESTAMP', HIE: 'HIE_DOCUMENT_EXCHANGE' } as Record<string, string>)[String(type)] ?? '—';
}
function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value));
}

async function collect() {
  const lease = leaseQuery.data.value;
  if (!lease || !collectConnector.value || busy.value) return;
  busy.value = 'collect'; notice.value = '';
  try {
    const result = await collectIntegrationMessages(lease, {
      connector_code: collectConnector.value,
      direction: collectDirection.value,
      simulation_scenario: collectScenario.value,
      record_count: collectCount.value,
    });
    notice.value = `已采集 ${result.messages.length} 条消息并写入对账窗口。`;
    collectOpen.value = false;
    await Promise.all([messagesQuery.refetch(), reconciliationsQuery.refetch()]);
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
  } finally {
    busy.value = '';
  }
}

async function reconcile(message: IntegrationMessageWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value) return;
  busy.value = message.message_id; notice.value = '';
  try {
    await reconcileIntegrationMessage(lease, message.message_id);
    notice.value = `消息 ${message.business_object} 已对账。`;
    await Promise.all([messagesQuery.refetch(), reconciliationsQuery.refetch()]);
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
  } finally {
    busy.value = '';
  }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">数据中心 / 集成平台</p>
        <h1>集成消息追踪与业务对账</h1>
        <p>这是受控仿真对账台：消息、失败和对账均留痕，但不创建或修改临床事实；字段映射仍由历史迁移转换器消费。</p>
      </div>
      <div class="toolbar-actions">
        <button class="button secondary" @click="messagesQuery.refetch()">刷新</button>
        <button class="button primary" @click="collectOpen = true">采集消息</button>
      </div>
    </div>

    <ClinicalPageState v-if="configLeaseQuery.isPending.value || leaseQuery.isPending.value" kind="loading" message="正在读取集成消息台账" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="messagesQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <section class="admin-metrics" aria-label="集成消息指标">
        <article><span>当前消息</span><strong>{{ messages.length }}</strong><small>按筛选条件</small></article>
        <article><span>待对账</span><strong>{{ pendingCount }}</strong><small>需业务确认后清零</small></article>
        <article><span>已送达</span><strong>{{ deliveredCount }}</strong><small>消息通道成功</small></article>
        <article><span>失败留痕</span><strong>{{ failedCount }}</strong><small>不可人工改写为已对账</small></article>
        <article><span>对账窗口</span><strong>{{ reconciliations.length }}</strong><small>按连接器 + 自然日</small></article>
      </section>

      <div class="integration-message-layout">
        <section class="admin-panel">
          <header>
            <div><h2>消息台账</h2><p>追踪号、业务对象与状态由服务端持久化，消息正文默认不展示。</p></div>
            <div class="filter-bar">
              <select v-model="connectorFilter" aria-label="按连接器筛选">
                <option value="">全部连接器</option>
                <option v-for="connector in connectors" :key="connector.config_id" :value="connector.config_key">{{ connector.display_name }}</option>
              </select>
              <select v-model="statusFilter" aria-label="按状态筛选">
                <option value="">全部状态</option>
                <option value="PENDING">待对账</option>
                <option value="DELIVERED">已送达</option>
                <option value="RECONCILED">已对账</option>
                <option value="FAILED">失败</option>
              </select>
            </div>
          </header>
          <div v-if="messages.length === 0" class="admin-empty">暂无消息，点击「采集消息」从模拟接口生成一批消息台账。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>追踪号</th><th>连接器 / 接口</th><th>业务对象</th><th>状态</th><th>发生时间</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="message in messages" :key="message.message_id">
                  <td><RouterLink class="link-button" :to="`/integration-messages/${message.message_id}`"><code>…{{ message.trace_id.slice(-8) }}</code></RouterLink></td>
                  <td>{{ message.connector_code }}<small><code>{{ message.interface_code }}</code></small></td>
                  <td>{{ message.business_object }}</td>
                  <td><span class="admin-status" :class="statusTone[message.message_status]">{{ statusLabels[message.message_status] }}</span></td>
                  <td>{{ formatDate(message.occurred_at) }}</td>
                  <td><button v-if="message.message_status !== 'RECONCILED' && message.message_status !== 'FAILED'" class="task-action" :disabled="Boolean(busy)" @click="reconcile(message)">对账</button><span v-else>—</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <aside class="admin-panel">
          <header><div><h2>对账窗口</h2><p>按连接器 + 自然日聚合消息状态。</p></div></header>
          <div v-if="reconciliations.length === 0" class="admin-empty">暂无对账窗口。</div>
          <div v-else class="reconciliation-list">
            <article v-for="item in reconciliations" :key="item.reconciliation_id" class="reconciliation-row">
              <b>{{ item.connector_code }}</b>
              <span class="status" :class="item.status === 'RECONCILED' ? 'green' : 'amber'">{{ item.status === 'RECONCILED' ? '已对账' : '开放' }}</span>
              <div class="folder-row"><span>发送</span><strong>{{ item.sent_count }}</strong></div>
              <div class="folder-row"><span>送达</span><strong>{{ item.delivered_count }}</strong></div>
              <div class="folder-row"><span>失败</span><strong>{{ item.error_count }}</strong></div>
              <div class="folder-row"><span>待对账</span><strong>{{ item.pending_count }}</strong></div>
              <small>{{ formatDate(item.window_start) }} 起 · 24 小时</small>
            </article>
          </div>
        </aside>
      </div>
    </template>

    <AdminActionDialog v-model:open="collectOpen" title="采集集成消息" description="从确定性模拟接口生成一批消息并持久化为可对账台账；相同请求具备幂等键，不会重复产生副作用。">
      <form class="admin-form" @submit.prevent="collect">
        <label><span>连接器</span>
          <select v-model="collectConnector" required>
            <option value="" disabled>选择已发布连接器</option>
            <option v-for="connector in connectors" :key="connector.config_id" :value="connector.config_key">{{ connector.display_name }} · {{ interfaceFor(connector.config_key) }}</option>
          </select>
        </label>
        <label><span>方向</span>
          <select v-model="collectDirection"><option value="OUTBOUND">院外发送</option><option value="INBOUND">院外回传</option></select>
        </label>
        <label><span>场景</span>
          <select v-model="collectScenario"><option value="SUCCESS">成功</option><option value="DEGRADED">降级（部分结果）</option><option value="UNAVAILABLE">不可用（写失败证据）</option></select>
        </label>
        <label><span>消息条数</span><input v-model.number="collectCount" type="number" min="12" max="200" /></label>
      </form>
      <template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" :disabled="!collectConnector || Boolean(busy)" @click="collect">采集</button></template>
    </AdminActionDialog>
  </section>
</template>

<style scoped>
.integration-message-layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 14px; align-items: start; }
.filter-bar { display: flex; gap: 8px; }
.filter-bar select { max-width: 220px; }
.reconciliation-list { display: grid; gap: 10px; padding: 12px; }
.reconciliation-row { display: grid; gap: 6px; padding: 10px; border: 1px solid var(--line); border-radius: 9px; background: #fff; }
.reconciliation-row b { font-size: 12px; }
.reconciliation-row small { color: var(--muted); font-size: 9px; }
@media (max-width: 900px) { .integration-message-layout { grid-template-columns: minmax(0, 1fr); } }
</style>
