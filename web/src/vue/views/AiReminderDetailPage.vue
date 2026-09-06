<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, nextTick, reactive, ref } from 'vue';
import type { ClinicalReminderConversionWire, ClinicalReminderWire } from '../../generated/contracts';
import { acknowledgeClinicalReminder, convertClinicalReminderToTask, createClinicalReminder, issueAssistantEncounterLease, listClinicalReminderConversions, listClinicalReminders, silenceClinicalReminder } from '../../api/assistant';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({ queryKey: ['assistant', 'reminder', 'lease'], queryFn: () => issueAssistantEncounterLease('REMINDER_DETAIL'), retry: false, staleTime: 5 * 60_000, gcTime: 0 });
const remindersQuery = useQuery({ queryKey: ['assistant', 'reminder', 'items'], queryFn: () => listClinicalReminders(leaseQuery.data.value!), enabled: () => Boolean(leaseQuery.data.value), retry: false });
const issue = computed(() => (leaseQuery.error.value ?? remindersQuery.error.value) ? toClinicalIssue(leaseQuery.error.value ?? remindersQuery.error.value) : null);
const reminders = computed(() => remindersQuery.data.value ?? []);
const conversionsQuery = useQuery({
  queryKey: computed(() => ['assistant', 'reminder', 'conversions', reminders.value.map((item) => item.reminder_id)]),
  queryFn: async () => Object.fromEntries(await Promise.all(reminders.value.map(async (reminder) => [
    reminder.reminder_id, await listClinicalReminderConversions(leaseQuery.data.value!, reminder.reminder_id),
  ]))) as Record<string, ClinicalReminderConversionWire[]>,
  enabled: () => Boolean(leaseQuery.data.value && !remindersQuery.isPending.value), retry: false,
});
const conversions = computed(() => conversionsQuery.data.value ?? {});
const pending = computed(() => reminders.value.filter((r) => r.status === 'PENDING').length);
const form = reactive({ reminderType: 'DRUG_INTERACTION', message: '', severity: 'WARNING' });
const justConverted = ref<Record<string, ClinicalReminderConversionWire>>({});
const busy = ref(false);
const notice = ref('');

function sevLabel(s: string) { const m: Record<string, string> = { INFO: '提示', WARNING: '警告', CRITICAL: '危急' }; return m[s] ?? s; }
function statusLabel(s: string) { const m: Record<string, string> = { PENDING: '待处理', ACKNOWLEDGED: '已确认', SILENCED: '已静默' }; return m[s] ?? s; }
function conversionFor(reminder: ClinicalReminderWire) {
  return justConverted.value[reminder.reminder_id] ?? conversions.value[reminder.reminder_id]?.[0] ?? null;
}

async function create() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.message.trim()) return;
  busy.value = true; notice.value = '';
  try {
    await createClinicalReminder(lease, { reminder_type: form.reminderType, message: form.message.trim(), severity: form.severity });
    form.message = ''; notice.value = '提醒已创建。'; await remindersQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; }
}
async function acknowledge(reminder: ClinicalReminderWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value) return;
  busy.value = true; notice.value = '';
  try { await acknowledgeClinicalReminder(lease, reminder); notice.value = '提醒已确认。'; await remindersQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; }
}
async function silence(reminder: ClinicalReminderWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value) return;
  busy.value = true; notice.value = '';
  try { await silenceClinicalReminder(lease, reminder); notice.value = '提醒已静默。'; await remindersQuery.refetch(); }
  catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; }
  finally { busy.value = false; }
}
async function convert(reminder: ClinicalReminderWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || conversionFor(reminder)) return;
  busy.value = true; notice.value = '';
  try {
    const conversion = await convertClinicalReminderToTask(lease, reminder);
    justConverted.value = { ...justConverted.value, [reminder.reminder_id]: conversion };
    notice.value = `已创建临床任务 …${conversion.clinical_task_id.slice(-8)}；任务中心可接手和编排，临床终态仍由来源业务回写。`;
    // The conversion query is keyed by the reminder ledger. Refresh it only after
    // Vue has applied the ledger update, otherwise the just-converted reminder can
    // retain an empty conversion map and hide its task return link.
    await remindersQuery.refetch();
    await nextTick();
    await conversionsQuery.refetch();
  } catch (error) { const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`; await conversionsQuery.refetch(); }
  finally { busy.value = false; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading"><div><p class="eyebrow">AI医助 EVA / 提醒</p><h1>主动提醒详情</h1><p>提醒可确认或静默；每条提醒最多转换一次临床任务，转换、任务来源和审计证据均由服务端持久化。</p></div></div>
    <ClinicalPageState v-if="leaseQuery.isPending.value || remindersQuery.isPending.value" kind="loading" message="正在读取提醒" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="remindersQuery.refetch()" />
    <template v-else>
      <section class="admin-metrics"><article><span>提醒</span><strong>{{ reminders.length }}</strong><small>当前就诊</small></article><article><span>待处理</span><strong>{{ pending }}</strong><small>需要医务人员查看</small></article></section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>
      <div class="admin-layout">
        <section class="admin-panel"><header><div><h2>提醒台账</h2><p>按严重度分级。</p></div><button class="button secondary" @click="remindersQuery.refetch()">刷新</button></header>
          <div v-if="!reminders.length" class="admin-empty">暂无提醒。</div>
          <div v-else class="admin-table-wrap"><table class="admin-table"><thead><tr><th>类型</th><th>消息</th><th>严重度</th><th>状态</th><th>关联任务</th><th>操作</th></tr></thead><tbody>
            <tr v-for="reminder in reminders" :key="reminder.reminder_id">
              <td><strong>{{ reminder.reminder_type }}</strong><small>…{{ reminder.reminder_id.slice(-8) }}</small></td>
              <td>{{ reminder.message }}</td>
              <td><span class="admin-status">{{ sevLabel(reminder.severity) }}</span></td>
              <td><span class="admin-status" :class="reminder.status.toLowerCase()">{{ statusLabel(reminder.status) }}</span></td>
              <td><RouterLink v-if="conversionFor(reminder)" class="task-action" :to="{ path: '/clinical-tasks', query: { view: 'overview', mode: 'outpatient', source: 'REMINDER' }}">任务 …{{ conversionFor(reminder)?.clinical_task_id.slice(-8) }}</RouterLink><span v-else>未转任务</span></td>
              <td>
                <button v-if="reminder.status === 'PENDING' && !conversionFor(reminder)" class="task-action" :disabled="busy" @click="convert(reminder)">转为任务</button>
                <button v-if="reminder.status === 'PENDING'" class="task-action" :disabled="busy" @click="acknowledge(reminder)">确认</button>
                <button v-if="reminder.status === 'PENDING'" class="task-action" :disabled="busy" @click="silence(reminder)">静默</button>
              </td>
            </tr>
          </tbody></table></div>
        </section>
        <section class="admin-panel admin-form-panel"><header><div><h2>新增提醒</h2><p>消息必填。</p></div></header>
          <form class="admin-form" @submit.prevent="create">
            <label><span>类型</span><select v-model="form.reminderType"><option value="DRUG_INTERACTION">药物相互作用</option><option value="ALLERGY">过敏</option><option value="DOSE">剂量</option><option value="FOLLOW_UP">随访</option><option value="OTHER">其他</option></select></label>
            <label><span>严重度</span><select v-model="form.severity"><option value="INFO">提示</option><option value="WARNING">警告</option><option value="CRITICAL">危急</option></select></label>
            <label><span>消息</span><textarea v-model="form.message" rows="3" required /></label>
            <button class="button primary full" :disabled="busy || !form.message.trim()">创建提醒</button>
          </form>
        </section>
      </div>
    </template>
  </section>
</template>
