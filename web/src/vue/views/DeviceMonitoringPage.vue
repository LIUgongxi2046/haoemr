<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { DeviceCatalogWire, DeviceObservationWire, DeviceStatusWire } from '../../generated/contracts';
import {
  collectDeviceTelemetry,
  bindDeviceStatusToEncounter,
  issueDeviceBindingLease,
  unbindDeviceStatusFromEncounter,
  issueDeviceLease,
  listDevices,
  listDeviceStatuses,
  listDeviceTelemetry,
} from '../../api/device';
import { clinicalContext } from '../../clinical-api';
import { toClinicalIssue } from '../clinical-error';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';

const metricLabels: Record<string, string> = {
  HR: '心率', SpO2: '血氧饱和度', NIBP_SYS: '收缩压', NIBP_DIA: '舒张压',
  RESP: '呼吸频率', TEMP: '体温', ETCO2: '呼气末二氧化碳',
};
const onlineLabels: Record<DeviceStatusWire['online_status'], string> = {
  ONLINE: '在线', DEGRADED: '降级', OFFLINE: '离线',
};
const onlineTone: Record<DeviceStatusWire['online_status'], string> = {
  ONLINE: 'green', DEGRADED: 'amber', OFFLINE: 'red',
};

const deviceFilter = ref('');
const collectOpen = ref(false);
const collectDevice = ref('');
const collectScenario = ref<'SUCCESS' | 'DEGRADED'>('SUCCESS');
const collectCount = ref(36);
const bindOpen = ref(false);
const selectedStatus = ref<DeviceStatusWire | null>(null);
const binding = reactive({ patientId: clinicalContext.patientId, encounterId: clinicalContext.encounterId, verificationMethod: '患者腕带与床位双标识核验' });
const unbindOpen = ref(false);
const unbinding = reactive({ reason: '患者离开监测区域，完成设备回收' });
const busy = ref('');
const notice = ref('');

const leaseQuery = useQuery({
  queryKey: ['device-monitoring', 'lease'], queryFn: issueDeviceLease,
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const devicesQuery = useQuery({
  queryKey: ['device-monitoring', 'devices'],
  queryFn: () => listDevices(leaseQuery.data.value!, 'ACTIVE'),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const statusesQuery = useQuery({
  queryKey: ['device-monitoring', 'statuses'],
  queryFn: () => listDeviceStatuses(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});
const observationsQuery = useQuery({
  queryKey: ['device-monitoring', 'observations', deviceFilter],
  queryFn: () => listDeviceTelemetry(leaseQuery.data.value!, deviceFilter.value || undefined),
  enabled: () => Boolean(leaseQuery.data.value), retry: false,
});

const devices = computed<DeviceCatalogWire[]>(() => devicesQuery.data.value ?? []);
const statuses = computed(() => statusesQuery.data.value ?? []);
const observations = computed(() => observationsQuery.data.value ?? []);
const onlineCount = computed(() => statuses.value.filter((item) => item.online_status === 'ONLINE').length);
const alarmCount = computed(() => statuses.value.filter((item) => item.alarm_state !== 'NONE').length);
const issue = computed(() => {
  const error = leaseQuery.error.value ?? devicesQuery.error.value ?? statusesQuery.error.value
    ?? observationsQuery.error.value;
  return error ? toClinicalIssue(error) : null;
});

function formatDate(value: string | null | undefined): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)) : '—';
}
function deviceName(code: string): string {
  return devices.value.find((item) => item.device_code === code)?.display_name ?? code;
}

async function collect() {
  const lease = leaseQuery.data.value;
  if (!lease || !collectDevice.value || busy.value) return;
  busy.value = 'collect'; notice.value = '';
  try {
    const result = await collectDeviceTelemetry(lease, {
      device_code: collectDevice.value,
      simulation_scenario: collectScenario.value,
      record_count: collectCount.value,
    });
    notice.value = `已采集 ${result.observations.length} 条遥测并刷新设备状态。`;
    collectOpen.value = false;
    await Promise.all([statusesQuery.refetch(), observationsQuery.refetch()]);
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
  } finally {
    busy.value = '';
  }
}
function openBinding(status: DeviceStatusWire) {
  selectedStatus.value = status;
  binding.patientId = clinicalContext.patientId;
  binding.encounterId = clinicalContext.encounterId;
  binding.verificationMethod = '患者腕带与床位双标识核验';
  bindOpen.value = true;
}
async function bind() {
  const lease = leaseQuery.data.value; const status = selectedStatus.value;
  if (!lease || !status || busy.value || !binding.patientId || !binding.encounterId || binding.verificationMethod.trim().length < 2) return;
  busy.value = 'bind'; notice.value = '';
  try {
    const bindingLease = await issueDeviceBindingLease(binding.patientId, binding.encounterId);
    await bindDeviceStatusToEncounter(bindingLease, status, { patient_id: binding.patientId, encounter_id: binding.encounterId, verification_method: binding.verificationMethod.trim() });
    notice.value = `${status.device_code} 已绑定到已核验患者就诊；后续遥测将按该绑定投影。`;
    bindOpen.value = false; await statusesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
function openUnbinding(status: DeviceStatusWire) {
  selectedStatus.value = status;
  unbinding.reason = '患者离开监测区域，完成设备回收';
  unbindOpen.value = true;
}
async function unbind() {
  const lease = leaseQuery.data.value; const status = selectedStatus.value;
  if (!lease || !status || busy.value || unbinding.reason.trim().length < 2) return;
  busy.value = 'unbind'; notice.value = '';
  try {
    await unbindDeviceStatusFromEncounter(lease, status, { reason: unbinding.reason.trim() });
    notice.value = `${status.device_code} 已解绑；解绑前的遥测和告警仍保留在原患者时间线中。`;
    unbindOpen.value = false; await statusesQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">数据中心 / 设备接入</p>
        <h1>设备接入与遥测监测</h1>
        <p>设备网关遥测持久化为可审计台账；在线状态、时钟偏差、校准状态与告警由服务端从观测记录汇总。</p>
      </div>
      <div class="toolbar-actions">
        <button class="button secondary" @click="observationsQuery.refetch()">刷新</button>
        <button class="button primary" @click="collectOpen = true">采集遥测</button>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value" kind="loading" message="正在读取设备遥测台账" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="observationsQuery.refetch()" />
    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <section class="admin-metrics" aria-label="设备状态指标">
        <article><span>接入设备</span><strong>{{ devices.length }}</strong><small>已发布目录</small></article>
        <article><span>在线</span><strong>{{ onlineCount }}</strong><small>有遥测状态的设备</small></article>
        <article><span>告警设备</span><strong>{{ alarmCount }}</strong><small>中/高级告警</small></article>
        <article><span>遥测记录</span><strong>{{ observations.length }}</strong><small>按筛选条件</small></article>
      </section>

      <div class="device-monitor-layout">
        <section class="admin-panel">
          <header>
            <div><h2>设备状态</h2><p>在线、时钟偏差、校准与告警状态由遥测记录汇总。</p></div>
            <select v-model="deviceFilter" aria-label="按设备筛选遥测"><option value="">全部设备</option><option v-for="device in devices" :key="device.device_id" :value="device.device_code">{{ device.display_name }}</option></select>
          </header>
          <div v-if="statuses.length === 0" class="admin-empty">暂无设备状态，点击「采集遥测」从设备网关生成一批状态。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>设备</th><th>在线状态</th><th>患者绑定</th><th>时钟偏差</th><th>校准</th><th>告警</th><th>最近观测</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="item in statuses" :key="item.device_code">
                  <td>{{ deviceName(item.device_code) }}<small><code>{{ item.device_code }}</code></small></td>
                  <td><span class="admin-status" :class="onlineTone[item.online_status]">{{ onlineLabels[item.online_status] }}</span></td>
                  <td>{{ item.bound_patient_id ? `已绑定 …${item.bound_patient_id.slice(-8)}` : '未绑定' }}</td>
                  <td>{{ item.clock_offset_seconds }}s</td>
                  <td>{{ item.calibration_status === 'VALID' ? '正常' : '待复核' }}</td>
                  <td><span class="admin-status" :class="item.alarm_state === 'NONE' ? 'green' : item.alarm_state === 'MEDIUM' ? 'amber' : 'red'">{{ item.alarm_state === 'NONE' ? '无' : item.alarm_state === 'MEDIUM' ? '中' : '高' }}</span></td>
                  <td>{{ formatDate(item.last_observed_at) }}</td>
                  <td><div class="toolbar-actions"><button v-if="item.bound_patient_id" class="task-action" @click="openUnbinding(item)">解绑</button><button v-else class="task-action" @click="openBinding(item)">绑定患者</button><RouterLink class="task-action" :to="`/device-monitoring/${item.device_code}`">详情</RouterLink></div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <aside class="admin-panel">
          <header><div><h2>最近遥测</h2><p>按观测时间倒序展示。</p></div></header>
          <div v-if="observations.length === 0" class="admin-empty">暂无遥测记录。</div>
          <div v-else class="observation-list">
            <article v-for="item in observations.slice(0, 12)" :key="item.observation_id" class="observation-row">
              <b>{{ metricLabels[item.metric] ?? item.metric }} <em>{{ item.metric_value }} {{ item.metric_unit }}</em></b>
              <span>{{ deviceName(item.device_code) }}</span>
              <span class="status" :class="item.alarm_level === 'NONE' ? 'green' : item.alarm_level === 'MEDIUM' ? 'amber' : 'red'">{{ item.alarm_level === 'NONE' ? '正常' : item.alarm_level }}</span>
              <small>{{ formatDate(item.observed_at) }}</small>
            </article>
          </div>
        </aside>
      </div>
    </template>

    <AdminActionDialog v-model:open="collectOpen" title="采集设备遥测" description="从确定性设备网关模拟接口生成一批遥测并持久化为设备状态与观测台账；相同请求具备幂等键。">
      <form class="admin-form" @submit.prevent="collect">
        <label><span>设备</span>
          <select v-model="collectDevice" required>
            <option value="" disabled>选择已接入设备</option>
            <option v-for="device in devices" :key="device.device_id" :value="device.device_code">{{ device.display_name }}</option>
          </select>
        </label>
        <label><span>场景</span>
          <select v-model="collectScenario"><option value="SUCCESS">正常</option><option value="DEGRADED">降级</option></select>
        </label>
        <label><span>遥测条数</span><input v-model.number="collectCount" type="number" min="12" max="200" /></label>
      </form>
      <template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" :disabled="!collectDevice || Boolean(busy)" @click="collect">采集</button></template>
    </AdminActionDialog>
    <AdminActionDialog v-model:open="bindOpen" title="绑定设备与患者就诊" description="必须核验患者、就诊和当前院区；绑定仅决定后续遥测的只读投影，不写入人工生命体征。">
      <form class="admin-form" @submit.prevent="bind"><label><span>患者 ID</span><input v-model="binding.patientId" required /></label><label><span>就诊 ID</span><input v-model="binding.encounterId" required /></label><label><span>双标识核验方式</span><textarea v-model="binding.verificationMethod" minlength="2" maxlength="256" required /></label></form>
      <template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" :disabled="Boolean(busy) || binding.verificationMethod.trim().length < 2" @click="bind">确认绑定</button></template>
    </AdminActionDialog>
    <AdminActionDialog v-model:open="unbindOpen" title="解绑设备与患者就诊" description="解绑后不再把新的遥测投影到该患者；解绑前已归属的遥测和告警会保留为可审计历史。">
      <form class="admin-form" @submit.prevent="unbind"><label><span>解绑原因</span><textarea v-model="unbinding.reason" minlength="2" maxlength="1000" required /></label></form>
      <template #footer="{ close }"><button class="button secondary" @click="close">取消</button><button class="button primary" :disabled="Boolean(busy) || unbinding.reason.trim().length < 2" @click="unbind">确认解绑</button></template>
    </AdminActionDialog>
  </section>
</template>

<style scoped>
.device-monitor-layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 14px; align-items: start; }
.observation-list { display: grid; gap: 8px; padding: 12px; }
.observation-row { display: grid; gap: 3px; padding: 9px; border: 1px solid var(--line); border-radius: 9px; background: #fff; }
.observation-row b { font-size: 12px; }
.observation-row b em { color: var(--blue); font-style: normal; }
.observation-row span { font-size: 10px; color: var(--muted); }
.observation-row small { font-size: 9px; color: var(--muted); }
@media (max-width: 900px) { .device-monitor-layout { grid-template-columns: minmax(0, 1fr); } }
</style>
