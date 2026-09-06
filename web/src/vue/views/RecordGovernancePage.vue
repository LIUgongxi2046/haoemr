<script setup lang="ts">
import EvaMessageContent from '../components/EvaMessageContent.vue';
import { useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';

import {
  clinicalContext,
  issueDocumentLease,
  loadDocumentGovernance,
  loadEncounterDocuments,
  runQualityChecks,
  signDocument,
} from '../../clinical-api';
import {
  createMedicalAgentRun,
  getMedicalAgentRun,
  issueMedicalAgentRunLease,
} from '../../api/medical-agents';
import type { MedicalAgentRunWire } from '../../generated/contracts';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import BusinessActionDialog from '../components/BusinessActionDialog.vue';
import RecordPatientStrip from '../components/RecordPatientStrip.vue';
import { toClinicalIssue } from '../clinical-error';
import { medicalAgentRunStateLabel, presentMedicalAgentResult } from '../medical-agent-run-presenter';

const route = useRoute();
const queryClient = useQueryClient();
const warningDisposition = ref('已核对警告并完成处置，不影响本次签署');
const busy = ref(false);
const notice = ref('');
const signDialogOpen = ref(false);
const agentBusy = ref(false);
const agentRun = ref<MedicalAgentRunWire | null>(null);
const signMode = computed(() => route.meta.contractId === 'record-sign');

const governance = useQuery({
  queryKey: ['clinical', 'record-governance'],
  queryFn: async () => {
    const lease = await issueDocumentLease();
    const documents = await loadEncounterDocuments(lease);
    const document = documents.find((item) => item.document_id === clinicalContext.documentId) ?? documents[0] ?? null;
    if (!document) return { lease, document: null, snapshot: null };
    return {
      lease,
      document,
      snapshot: await loadDocumentGovernance(lease, document.document_id, document.document_version_id),
    };
  },
  retry: false,
  staleTime: 0,
  gcTime: 0,
});

const issue = computed(() => governance.error.value ? toClinicalIssue(governance.error.value) : null);
const document = computed(() => governance.data.value?.document);
const snapshot = computed(() => governance.data.value?.snapshot);
const qualityRun = computed(() => snapshot.value?.quality_run);
const openFindings = computed(() => snapshot.value?.quality_findings.filter((item) => item.state === 'OPEN') ?? []);
const blocking = computed(() => openFindings.value.filter((item) => item.severity === 'BLOCKING'));
const warnings = computed(() => openFindings.value.filter((item) => item.severity === 'WARNING'));
const canSign = computed(() => Boolean(
  document.value && document.value.status !== 'SIGNED'
  && qualityRun.value && qualityRun.value.outcome !== 'BLOCKED'
  && blocking.value.length === 0,
));
const readinessPercent = computed(() => Math.max(0, Math.min(100,
  100 - (blocking.value.length * 50) - (warnings.value.length * 10) - (qualityRun.value ? 0 : 25))));

async function rerunQuality() {
  const data = governance.data.value;
  if (!data?.document || busy.value || data.document.status !== 'DRAFT') return;
  busy.value = true;
  notice.value = '';
  try {
    const findings = await runQualityChecks(data.lease, data.document);
    notice.value = findings.length === 0 ? '确定性质控通过，可以进入签署。' : `质控完成，发现 ${findings.length} 项问题。`;
    await governance.refetch();
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = false; }
}

async function signCurrentVersion() {
  const data = governance.data.value;
  if (!data?.document || !canSign.value || busy.value) return;
  busy.value = true;
  notice.value = '';
  try {
    const evidence = await signDocument(data.lease, data.document, warningDisposition.value);
    notice.value = `签署证据已创建：${evidence.signature_status}`;
    signDialogOpen.value = false;
    await governance.refetch();
    await queryClient.invalidateQueries({ queryKey: ['clinical', 'current-document'] });
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
  } finally {
    busy.value = false;
  }
}

const terminalAgentStates = new Set<MedicalAgentRunWire['state']>([
  'WAITING_FOR_REVIEW', 'COMPLETED', 'PARTIAL', 'BLOCKED', 'FAILED', 'CANCELLED',
]);

async function runSemanticQcAgent() {
  const data = governance.data.value;
  if (!data?.document || agentBusy.value) return;
  agentBusy.value = true;
  notice.value = '';
  try {
    const lease = await issueMedicalAgentRunLease(clinicalContext.patientId, clinicalContext.encounterId);
    agentRun.value = await createMedicalAgentRun(lease, {
      patientId: clinicalContext.patientId,
      encounterId: clinicalContext.encounterId,
      mainAgentCode: 'RECORD_QC',
      stageCode: signMode.value ? 'PRE_SIGN' : 'WRITING',
      targetType: 'DOCUMENT',
      targetId: data.document.document_id,
      objective: `对病历 ${data.document.document_id} 当前不可变版本 v${data.document.version_no} 进行语义质控；仅输出带来源的问题候选，不改写正文。`,
      authorizationLevel: 'READ_ONLY',
      contextScopes: ['RECORDS', 'RESULTS', 'ATTACHMENTS'],
    });
    for (let poll = 0; poll < 90 && !terminalAgentStates.has(agentRun.value.state); poll += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 800));
      agentRun.value = await getMedicalAgentRun(
        lease, clinicalContext.patientId, clinicalContext.encounterId, agentRun.value.run_id,
      );
    }
    notice.value = terminalAgentStates.has(agentRun.value.state)
      ? '语义质控已完成；结果是人工复核候选，不会自动写入或解除硬阻断。'
      : '语义质控仍在后台运行，运行记录已持久化。';
  } catch (error) {
    const next = toClinicalIssue(error);
    notice.value = `${next.code}：${next.message}`;
  } finally {
    agentBusy.value = false;
  }
}

function outcomeLabel(outcome?: string) {
  return ({ PASSED: '已通过', WARNING: '有警告', BLOCKED: '已阻断', NOT_RUN: '未运行' } as Record<string, string>)[outcome ?? 'NOT_RUN'];
}

</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-heading"><div><h1>{{ signMode ? '病历签署确认' : '病历质控与审签中心' }}</h1><p>{{ signMode ? '受保护终态 · 签署后内容不可覆盖' : '硬规则、机构规则、医助建议和人工缺陷分别治理' }}</p></div><div class="toolbar-actions"><button class="btn" type="button" :disabled="busy || document?.status !== 'DRAFT'" @click="rerunQuality">运行确定性质控</button><button class="btn" type="button" :disabled="agentBusy || !document" @click="runSemanticQcAgent">{{ agentBusy ? '医助复核中…' : '医助语义复核' }}</button><RouterLink v-if="!signMode" class="btn primary" to="/record-sign">进入签署确认</RouterLink><RouterLink v-else class="btn" to="/record-qc">返回质控</RouterLink></div></div>
    <RecordPatientStrip />
    <ClinicalPageState v-if="governance.isPending.value" kind="loading" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="governance.refetch()" />
    <div v-else-if="!document" class="record-evidence-empty"><h2>本次就诊尚无可治理病历</h2><p>创建病历后才能运行质控并进入审签。</p>
      <RouterLink class="button primary" to="/record">创建病历</RouterLink></div>
    <div v-else-if="snapshot" class="governance-workspace">
      <div v-if="notice" class="notice" role="status">{{ notice }}</div>
      <div class="grid qc-layout record-real-qc">
        <section class="card"><div class="card-head">当前草稿 v{{ document.version_no }} · 质量问题 <span class="status" :class="blocking.length ? 'red' : 'green'">{{ blocking.length }} 阻断</span></div><table class="table"><thead><tr><th>类型</th><th>问题</th><th>状态</th><th>定位</th><th></th></tr></thead><tbody><tr v-if="openFindings.length === 0"><td>确定性规则</td><td><b>{{ qualityRun ? '当前无未闭环规则问题' : '尚未运行质控' }}</b></td><td><span class="status" :class="qualityRun ? 'green' : 'amber'">{{ qualityRun ? outcomeLabel(qualityRun.outcome) : '待运行' }}</span></td><td>整份文书</td><td><RouterLink class="btn sm" to="/record-editor">定位</RouterLink></td></tr><tr v-for="finding in openFindings" :key="finding.finding_id"><td>{{ finding.severity === 'BLOCKING' ? '硬规则' : '机构规则' }}</td><td><b>{{ finding.message }}</b><br><span class="meta">{{ finding.rule_code }} · {{ finding.rule_version }}</span></td><td><span class="status" :class="finding.severity === 'BLOCKING' ? 'red' : 'amber'">{{ finding.severity === 'BLOCKING' ? '阻断' : '待处理' }}</span></td><td>{{ finding.field_path || '文书级' }}</td><td><RouterLink class="btn sm" to="/record-editor">定位</RouterLink></td></tr></tbody></table><div class="card-body"><div class="notice info"><div class="notice-title">缺陷失效规则</div>正文、来源、模板或规则版本变化后，相关“已处理”状态自动重新评估；豁免不跨版本继承。</div></div></section>
        <aside class="card"><div class="card-head">审签责任与最终状态</div><div class="card-body"><div class="completion-ring warning" :style="{ background: `conic-gradient(#e99d23 ${readinessPercent}%, #e8edf2 0)` }"><b>{{ readinessPercent }}%</b><span>签署准备度</span></div><div class="queue-item"><div class="queue-title">作者 · 当前医生<span class="status green">有效</span></div></div><div class="queue-item"><div class="queue-title">签署策略 · {{ snapshot.signature_policy?.required_signature_level || '正式医师单签' }}<span class="status green">已匹配</span></div></div><div class="queue-item"><div class="queue-title">最终版本 · v{{ document.version_no }}<span class="status" :class="document.status === 'SIGNED' ? 'green' : 'amber'">{{ document.status }}</span></div></div><div class="queue-item"><div class="queue-title">CA/时间戳<span class="status" :class="snapshot.signatures.length ? 'green' : 'amber'">{{ snapshot.signatures.length ? '已有证据' : '签署时生成' }}</span></div></div><button class="btn primary record-sign-action" type="button" :disabled="!canSign || busy" @click="signDialogOpen = true">{{ document.status === 'SIGNED' ? '当前版本已签署' : '预览最终文书并签署' }}</button><small v-if="!canSign && document.status !== 'SIGNED'">必须先完成当前内容确定性质控并清零阻断问题。</small></div></aside>
      </div>
      <section v-if="agentRun" class="card"><div class="card-head">AI 语义质控候选 <span class="status" :class="['FAILED', 'BLOCKED'].includes(agentRun.state) ? 'red' : terminalAgentStates.has(agentRun.state) ? 'green' : 'amber'">{{ medicalAgentRunStateLabel(agentRun.state) }}</span></div><div class="card-body"><p><b>运行编号：</b>{{ agentRun.run_id }} · <b>目标：</b>{{ agentRun.target_type }} / {{ agentRun.target_id }}</p><div class="notice info"><div class="notice-title">仅供人工复核</div><EvaMessageContent :text="presentMedicalAgentResult(agentRun, 'markdown')" /></div><small>Agent 使用只读授权，确定性规则、签署门禁和临床责任不会被 AI 结果替代。</small></div></section>
      <BusinessActionDialog :open="signDialogOpen" title="确认签署当前不可变版本" description="签署会生成绑定当前内容哈希的签名证据；签后修改必须走更正/补记流程。" eyebrow="病历 / 审签门禁" confirm-label="确认签署并留存证据" danger :busy="busy" width="wide" @cancel="signDialogOpen = false" @confirm="signCurrentVersion"><p class="dialog-warning">v{{ document.version_no }} · 内容哈希 {{ document.content_hash.slice(0, 18) }}… · {{ warnings.length }} 条警告</p><label>警告处置说明<textarea v-model="warningDisposition" rows="4" minlength="2" maxlength="2000" /></label></BusinessActionDialog>
    </div>
  </section>
</template>
