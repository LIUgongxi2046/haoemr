<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import type { DeviceAlertWire, DeviceAlertTransitionRequestWire, DeviceStatusWire } from '../../generated/contracts';
import {
  issueDeviceLease, listDeviceAlerts, listDeviceStatuses, listDeviceTelemetry, transitionDeviceAlert,
} from '../../api/device';
import { toClinicalIssue } from '../clinical-error';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';

const route = useRoute();
const deviceCode = computed(() => String(route.params.deviceCode ?? ''));
const metricLabels: Record<string, string> = {
  HR: '心率', SpO2: '血氧饱和度', NIBP_SYS: '收缩压', NIBP_DIA: '舒张压',
  RESP: '呼吸频率', TEMP: '体温', ETCO2: '呼气末二氧化碳',
};

const leaseQuery = useQuery({
  queryKey: ['device-detail', 'lease'], queryFn: issueDeviceLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const statusesQuery = useQuery({
  queryKey: ['device-detail', 'status', deviceCode],
  queryFn: () => listDeviceStatuses(leaseQuery.data.value!, deviceCode.value),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const observationsQuery = useQuery({
  queryKey: ['device-detail', 'observations', deviceCode],
  queryFn: () => listDeviceTelemetry(leaseQuery.data.value!, deviceCode.value),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const alertsQuery = useQuery({
  queryKey: ['device-detail', 'alerts', deviceCode],
  queryFn: () => listDeviceAlerts(leaseQuery.data.value!, deviceCode.value),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});

const status = computed<DeviceStatusWire | null>(() => statusesQuery.data.value?.[0] ?? null);
const observations = computed(() => observationsQuery.data.value ?? []);
const alerts = computed(() => alertsQuery.data.value ?? []);
const selectedAlert = ref<DeviceAlertWire | null>(null);
const alertAction = ref<DeviceAlertTransitionRequestWire['action']>('ACKNOWLEDGE');
const alertNote = ref('');
const busy = ref(false);
const notice = ref('');
const issue = computed(() => {
  const error = leaseQuery.error.value ?? statusesQuery.error.value ?? observationsQuery.error.value ?? alertsQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});

function formatDate(value: string | null | undefined): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)) : '—';
}
function stateLabel(value: DeviceAlertWire['state']) {
  return ({ OPEN: '待处置', ACKNOWLEDGED: '已确认', ESCALATED: '已升级', CLOSED: '已关闭' } as Record<string, string>)[value] ?? value;
}
function openAction(alert: DeviceAlertWire, action: DeviceAlertTransitionRequestWire['action']) {
  selectedAlert.value = alert; alertAction.value = action; alertNote.value = '';
}
async function applyAlertAction() {
  const lease = leaseQuery.data.value; const alert = selectedAlert.value;
  if (!lease || !alert || busy.value || alertNote.value.trim().length < 2) return;
  busy.value = true;
  try {
    await transitionDeviceAlert(lease, alert, alertAction.value, alertNote.value.trim());
    notice.value = `${alert.device_code} · ${alert.metric} 告警已${stateLabel(alertAction.value === 'ACKNOWLEDGE' ? 'ACKNOWLEDGED' : alertAction.value === 'ESCALATE' ? 'ESCALATED' : 'CLOSED')}，操作者和说明已留痕。`;
    selectedAlert.value = null;
    await Promise.all([alertsQuery.refetch(), statusesQuery.refetch()]);
  } catch (error) { const issue = toClinicalIssue(error); notice.value = `${issue.code}：${issue.message}`; }
  finally { busy.value = false; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">数据中心 / 设备接入 / 设备详情</p>
        <h1>设备遥测详情</h1>
        <p>{{ deviceCode }} · 观测记录与运行状态只读展示。</p>
      </div>
      <div class="toolbar-actions"><RouterLink class="button secondary" to="/device-monitoring">返回设备监测</RouterLink></div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || observationsQuery.isPending.value" kind="loading" message="正在读取设备遥测详情" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="observationsQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="record-command-message" role="status">{{ notice }}</p>
      <section v-if="status" class="admin-metrics">
        <article><span>在线状态</span><strong>{{ status.online_status }}</strong><small>最近观测 {{ formatDate(status.last_observed_at) }}</small></article>
        <article><span>时钟偏差</span><strong>{{ status.clock_offset_seconds }}s</strong><small>超过 30s 进入人工复核</small></article>
        <article><span>校准状态</span><strong>{{ status.calibration_status === 'VALID' ? '正常' : '待复核' }}</strong><small>由设备网关上报</small></article>
        <article><span>告警状态</span><strong>{{ status.alarm_state === 'NONE' ? '无' : status.alarm_state }}</strong><small>最高级告警</small></article>
      </section>

      <section class="admin-panel">
        <header><div><h2>活动告警处置</h2><p>确认、升级与关闭均需留存责任人和处置说明；关闭后只由新的异常观测重新开启。</p></div></header>
        <div v-if="alerts.length === 0" class="admin-empty">该设备当前没有活动告警。</div>
        <div v-else class="admin-table-wrap">
          <table class="admin-table"><thead><tr><th>指标</th><th>等级</th><th>状态</th><th>摘要</th><th>开启时间</th><th>操作</th></tr></thead><tbody>
            <tr v-for="alert in alerts" :key="alert.device_alert_id"><td>{{ metricLabels[alert.metric] ?? alert.metric }}</td><td><span class="admin-status" :class="alert.alarm_level === 'HIGH' ? 'red' : 'amber'">{{ alert.alarm_level }}</span></td><td>{{ stateLabel(alert.state) }}</td><td>{{ alert.summary }}</td><td>{{ formatDate(alert.opened_at) }}</td><td><div class="alert-actions"><button v-if="alert.state === 'OPEN'" class="button secondary" @click="openAction(alert,'ACKNOWLEDGE')">确认</button><button v-if="['OPEN','ACKNOWLEDGED'].includes(alert.state)" class="button secondary" @click="openAction(alert,'ESCALATE')">升级</button><button v-if="alert.state !== 'CLOSED'" class="button primary" @click="openAction(alert,'CLOSE')">关闭</button></div></td></tr>
          </tbody></table>
        </div>
      </section>

      <section class="admin-panel">
        <header><div><h2>观测记录</h2><p>按观测时间倒序，遥测值与质量等级由设备网关生成。</p></div></header>
        <div v-if="observations.length === 0" class="admin-empty">该设备暂无遥测记录。</div>
        <div v-else class="admin-table-wrap">
          <table class="admin-table">
            <thead><tr><th>指标</th><th>数值</th><th>单位</th><th>质量</th><th>告警</th><th>观测时间</th></tr></thead>
            <tbody>
              <tr v-for="item in observations" :key="item.observation_id">
                <td>{{ metricLabels[item.metric] ?? item.metric }}</td>
                <td>{{ item.metric_value }}</td>
                <td>{{ item.metric_unit }}</td>
                <td><span class="admin-status" :class="item.quality === 'VERIFIED' ? 'green' : 'amber'">{{ item.quality === 'VERIFIED' ? '已核验' : '可疑' }}</span></td>
                <td><span class="admin-status" :class="item.alarm_level === 'NONE' ? 'green' : item.alarm_level === 'MEDIUM' ? 'amber' : 'red'">{{ item.alarm_level === 'NONE' ? '无' : item.alarm_level }}</span></td>
                <td>{{ formatDate(item.observed_at) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
    <BusinessActionDialog :open="Boolean(selectedAlert)" :title="`${alertAction === 'ACKNOWLEDGE' ? '确认' : alertAction === 'ESCALATE' ? '升级' : '关闭'}设备告警`" eyebrow="设备监护 / 人工处置" :description="selectedAlert ? `${selectedAlert.device_code} · ${selectedAlert.metric} · ${selectedAlert.summary}` : ''" :confirm-label="alertAction === 'ACKNOWLEDGE' ? '确认告警' : alertAction === 'ESCALATE' ? '升级告警' : '关闭告警'" :busy="busy" :confirm-disabled="alertNote.trim().length < 2" @cancel="selectedAlert=null" @confirm="applyAlertAction"><label><span>处置说明</span><textarea v-model="alertNote" minlength="2" maxlength="1000" rows="4" required placeholder="记录当前评估、已通知对象、处置或关闭依据" /></label></BusinessActionDialog>
  </section>
</template>

<style scoped>
.alert-actions{display:flex;gap:6px;flex-wrap:wrap}.alert-actions .button{min-height:30px;padding:4px 8px;font-size:12px}
</style>
