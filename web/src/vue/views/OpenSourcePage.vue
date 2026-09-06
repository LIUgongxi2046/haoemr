<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { ReleaseDownloadEventWire, ReleaseMetricSnapshotWire } from '../../generated/contracts';
import {
  countValidReleaseDownloads,
  issueDataLease,
  listReleaseDownloadEvents,
  listReleaseMetricSnapshots,
  recordReleaseDownloadEvent,
  recordReleaseMetricSnapshot,
} from '../../api/data';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

type MetricType = ReleaseMetricSnapshotWire['metric_type'];
type Channel = ReleaseDownloadEventWire['channel'];

const metricOptions: MetricType[] = ['STARS', 'DOWNLOADS', 'ACTIVE_INSTALLS'];
const channelOptions: Channel[] = ['GITHUB', 'WEBSITE', 'PACKAGE_REGISTRY', 'DOCKER_HUB'];
const metricLabels: Record<MetricType, string> = { STARS: '星标', DOWNLOADS: '下载量', ACTIVE_INSTALLS: '活跃安装' };
const channelLabels: Record<Channel, string> = { GITHUB: 'GitHub', WEBSITE: '官网', PACKAGE_REGISTRY: '包仓库', DOCKER_HUB: 'Docker Hub' };
const sourceRepositoryUrl = 'https://github.com/LIUgongxi2046/haoemr';
const licenseUrl = `${sourceRepositoryUrl}/blob/main/LICENSE`;
const commercialLicenseUrl = `${sourceRepositoryUrl}/blob/main/COMMERCIAL_LICENSE.md`;
const trademarkPolicyUrl = `${sourceRepositoryUrl}/blob/main/TRADEMARKS.md`;

const metricType = ref<MetricType>('STARS');
const channelFilter = ref('');

const leaseQuery = useQuery({
  queryKey: ['data', 'opensource', 'lease'],
  queryFn: () => issueDataLease('OPENSOURCE_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const snapshotsQuery = useQuery({
  queryKey: ['data', 'opensource', 'snapshots', metricType],
  queryFn: () => listReleaseMetricSnapshots(leaseQuery.data.value!, metricType.value),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const eventsQuery = useQuery({
  queryKey: ['data', 'opensource', 'events', channelFilter],
  queryFn: () => listReleaseDownloadEvents(leaseQuery.data.value!, channelFilter.value || undefined),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const validCountQuery = useQuery({
  queryKey: ['data', 'opensource', 'valid-count', channelFilter],
  queryFn: () => countValidReleaseDownloads(leaseQuery.data.value!, channelFilter.value || undefined),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const leaseIssue = computed(() => leaseQuery.error.value ? toClinicalIssue(leaseQuery.error.value) : null);
const snapshots = computed(() => snapshotsQuery.data.value ?? []);
const events = computed(() => eventsQuery.data.value ?? []);
const validCount = computed(() => validCountQuery.data.value ?? null);
const snapshotIssue = computed(() => snapshotsQuery.error.value ? toClinicalIssue(snapshotsQuery.error.value) : null);
const eventIssue = computed(() => (eventsQuery.error.value ?? validCountQuery.error.value)
  ? toClinicalIssue(eventsQuery.error.value ?? validCountQuery.error.value) : null);

const snapshotForm = reactive({ metricValue: 0, source: '', snapshotDate: new Date().toISOString().slice(0, 10) });
const eventForm = reactive({ channel: 'GITHUB' as Channel, sourceIp: '', userAgent: '', fingerprintHash: '' });
const busy = ref('');
const notice = ref('');

function formatDate(value: string | null | undefined) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}

async function recordSnapshot() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !snapshotForm.source.trim() || !snapshotForm.snapshotDate) return;
  busy.value = 'snapshot'; notice.value = '';
  try {
    await recordReleaseMetricSnapshot(lease, {
      metric_type: metricType.value,
      metric_value: snapshotForm.metricValue,
      source: snapshotForm.source.trim(),
      snapshot_date: snapshotForm.snapshotDate,
    });
    snapshotForm.source = '';
    notice.value = '发布指标快照已记录，审计链与事件出箱已同步记录。';
    await snapshotsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function recordEvent() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !eventForm.fingerprintHash.trim()) return;
  busy.value = 'event'; notice.value = '';
  try {
    await recordReleaseDownloadEvent(lease, {
      channel: eventForm.channel,
      source_ip: eventForm.sourceIp.trim() || null,
      user_agent: eventForm.userAgent.trim() || null,
      fingerprint_hash: eventForm.fingerprintHash.trim(),
      downloaded_at: new Date().toISOString(),
    });
    eventForm.sourceIp = ''; eventForm.userAgent = ''; eventForm.fingerprintHash = '';
    notice.value = '下载事件已记录，机器人判定与有效计数已刷新。';
    await Promise.all([eventsQuery.refetch(), validCountQuery.refetch()]);
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">数据中心 / 开源</p>
        <h1>开源发布指标</h1>
        <p>记录发布指标快照与下载事件，识别机器人流量并统计有效下载数；所有变更使用幂等键、审计链与事件出箱。</p>
      </div>
      <div class="admin-inline-tools">
        <label class="admin-code-input"><span>指标类型</span>
          <select v-model="metricType"><option v-for="metric in metricOptions" :key="metric" :value="metric">{{ metricLabels[metric] }}</option></select>
        </label>
        <button class="button secondary" :disabled="Boolean(busy)" @click="snapshotsQuery.refetch()">刷新指标</button>
      </div>
    </div>

    <section class="admin-panel" aria-labelledby="open-source-license-title">
      <header>
        <div>
          <h2 id="open-source-license-title">开源许可与源码</h2>
          <p>HaoEMR Community Edition 使用 AGPL-3.0-only；商业使用可以进行，但必须遵守该许可证。修改后的网络服务须按 AGPL 第 13 条向服务用户提供对应源码。</p>
        </div>
        <div class="admin-inline-tools">
          <a class="button primary" :href="sourceRepositoryUrl" target="_blank" rel="noreferrer">查看官方源码</a>
          <a class="button secondary" :href="licenseUrl" target="_blank" rel="noreferrer">查看许可证</a>
        </div>
      </header>
      <div class="admin-metrics" aria-label="开源授权信息">
        <article><span>社区许可证</span><strong>AGPL-3.0-only</strong><small>网络修改版需向用户提供对应源码</small></article>
        <article><span>闭源使用</span><strong>另行授权</strong><small><a :href="commercialLicenseUrl" target="_blank" rel="noreferrer">商业授权说明</a></small></article>
        <article><span>品牌使用</span><strong>独立管理</strong><small><a :href="trademarkPolicyUrl" target="_blank" rel="noreferrer">HaoEMR 商标政策</a></small></article>
      </div>
    </section>

    <ClinicalPageState v-if="leaseQuery.isPending.value" kind="loading" message="正在建立数据中心上下文" />
    <ClinicalPageState v-else-if="leaseIssue" kind="error" :code="leaseIssue.code" :message="leaseIssue.message" @retry="leaseQuery.refetch()" />

    <template v-else>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <section class="admin-panel">
        <header>
          <div><h2>发布指标快照 · {{ metricLabels[metricType] }}</h2><p>按指标类型记录快照值与采集来源。</p></div>
        </header>
        <ClinicalPageState v-if="snapshotsQuery.isPending.value" kind="loading" message="正在读取指标快照" />
        <ClinicalPageState v-else-if="snapshotIssue" kind="error" :code="snapshotIssue.code" :message="snapshotIssue.message" @retry="snapshotsQuery.refetch()" />
        <div v-else class="admin-layout">
          <section>
            <div v-if="snapshots.length === 0" class="admin-empty" role="status">暂无 {{ metricLabels[metricType] }} 快照，可在右侧记录。</div>
            <div v-else class="admin-table-wrap">
              <table class="admin-table">
                <thead><tr><th>指标值</th><th>来源</th><th>快照日期</th></tr></thead>
                <tbody>
                  <tr v-for="snapshot in snapshots" :key="snapshot.snapshot_id">
                    <td><strong>{{ snapshot.metric_value }}</strong><small>v{{ snapshot.row_version }}</small></td>
                    <td>{{ snapshot.source }}</td>
                    <td>{{ snapshot.snapshot_date }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <section class="admin-form-panel">
            <form class="admin-form" @submit.prevent="recordSnapshot">
              <label><span>指标值</span><input v-model.number="snapshotForm.metricValue" type="number" min="0" step="1" required /></label>
              <label><span>来源</span><input v-model="snapshotForm.source" maxlength="128" required placeholder="例：GitHub API" /></label>
              <label><span>快照日期</span><input v-model="snapshotForm.snapshotDate" type="date" required /></label>
              <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'snapshot' ? '正在记录…' : '记录快照' }}</button>
            </form>
          </section>
        </div>
      </section>

      <section class="admin-panel">
        <header>
          <div><h2>下载事件</h2><p>按渠道记录下载事件，识别机器人流量并统计有效下载数。</p></div>
          <div class="admin-inline-tools">
            <label class="admin-code-input"><span>渠道筛选</span>
              <select v-model="channelFilter"><option value="">全部渠道</option><option v-for="channel in channelOptions" :key="channel" :value="channel">{{ channelLabels[channel] }}</option></select>
            </label>
            <button class="button secondary" :disabled="Boolean(busy)" @click="eventsQuery.refetch()">刷新事件</button>
          </div>
        </header>
        <ClinicalPageState v-if="eventsQuery.isPending.value || validCountQuery.isPending.value" kind="loading" message="正在读取下载事件" />
        <ClinicalPageState v-else-if="eventIssue" kind="error" :code="eventIssue.code" :message="eventIssue.message" @retry="eventsQuery.refetch()" />
        <div v-else class="admin-layout">
          <section>
            <div class="admin-metrics" aria-label="下载统计">
              <article><span>有效下载</span><strong>{{ validCount?.valid_count ?? 0 }}</strong><small>{{ validCount?.channel ? channelLabels[validCount.channel] : '全部渠道' }}</small></article>
            </div>
            <div v-if="events.length === 0" class="admin-empty" role="status">暂无下载事件，可在右侧记录。</div>
            <div v-else class="admin-table-wrap">
              <table class="admin-table">
                <thead><tr><th>渠道</th><th>指纹哈希</th><th>来源 IP</th><th>机器人</th><th>下载时间</th></tr></thead>
                <tbody>
                  <tr v-for="event in events" :key="event.download_event_id">
                    <td>{{ channelLabels[event.channel] }}</td>
                    <td><code>{{ event.fingerprint_hash }}</code></td>
                    <td>{{ event.source_ip ?? '—' }}</td>
                    <td><span class="admin-status" :class="event.is_robot ? 'inactive' : 'active'">{{ event.is_robot ? '机器人' : '真人' }}</span></td>
                    <td>{{ formatDate(event.downloaded_at) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <section class="admin-form-panel">
            <form class="admin-form" @submit.prevent="recordEvent">
              <label><span>渠道</span><select v-model="eventForm.channel"><option v-for="channel in channelOptions" :key="channel" :value="channel">{{ channelLabels[channel] }}</option></select></label>
              <label><span>来源 IP（可选）</span><input v-model="eventForm.sourceIp" maxlength="64" placeholder="例：203.0.113.7" /></label>
              <label><span>User-Agent（可选）</span><input v-model="eventForm.userAgent" maxlength="256" placeholder="例：curl/8.4.0" /></label>
              <label><span>指纹哈希</span><input v-model="eventForm.fingerprintHash" maxlength="128" required placeholder="设备/请求指纹哈希" /></label>
              <button class="button primary full" :disabled="Boolean(busy)">{{ busy === 'event' ? '正在记录…' : '记录下载事件' }}</button>
            </form>
          </section>
        </div>
      </section>
    </template>
  </section>
</template>
