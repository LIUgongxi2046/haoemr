<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  approvePathwayKnowledge,
  getPathwayReviewQueue,
  issuePathwayKnowledgeLease,
  reviewPathwayKnowledge,
  submitPathwayKnowledge,
} from '../../api/pathway-knowledge';
import type { ContextLeaseWire, PathwayKnowledgeVersionWire, PathwayReviewQueueItemWire } from '../../generated/contracts';

const lease = ref<ContextLeaseWire | null>(null);
const busy = ref(false);
const notice = ref('');
const items = ref<PathwayReviewQueueItemWire[]>([]);

const draft = computed(() => items.value.filter((i) => i.status === 'DRAFT'));
const inReview = computed(() => items.value.filter((i) => i.status === 'IN_REVIEW'));
const approved = computed(() => items.value.filter((i) => i.status === 'APPROVED'));

async function ensureLease(): Promise<ContextLeaseWire> {
  if (!lease.value) lease.value = await issuePathwayKnowledgeLease();
  return lease.value;
}

async function refresh() {
  const l = await ensureLease();
  items.value = await getPathwayReviewQueue(l);
}

async function act(fn: (l: ContextLeaseWire, id: string) => Promise<PathwayKnowledgeVersionWire>, item: PathwayReviewQueueItemWire, label: string) {
  const l = await ensureLease();
  busy.value = true;
  notice.value = '';
  try {
    await fn(l, item.pathway_version_id);
    notice.value = `${item.display_name} ${label}`;
    await refresh();
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busy.value = false;
  }
}

onMounted(() => { void refresh(); });
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-head">
      <div class="page-title">
        <h1>审核队列</h1>
        <p>按状态推进临床路径版本（提交 → 审核 → 审批发布，职责分离）</p>
      </div>
      <div class="head-actions"><button class="btn" :disabled="busy" @click="refresh">刷新</button></div>
    </div>
    <p v-if="notice" class="notice-banner">{{ notice }}</p>

    <div class="board">
      <div class="board-col">
        <h3>待提交 · 草稿（{{ draft.length }}）</h3>
        <div v-for="i in draft" :key="i.pathway_version_id" class="board-card">
          <b>{{ i.display_name }}</b>
          <p>v{{ i.version_no }} · {{ i.diagnosis_code }}</p>
          <button class="btn" :disabled="busy" @click="act(submitPathwayKnowledge, i, '已提交审核')">提交</button>
        </div>
        <div v-if="!draft.length" class="board-empty">空</div>
      </div>
      <div class="board-col">
        <h3>待审核（{{ inReview.length }}）</h3>
        <div v-for="i in inReview" :key="i.pathway_version_id" class="board-card">
          <b>{{ i.display_name }}</b>
          <p>v{{ i.version_no }} · {{ i.diagnosis_code }}</p>
          <button class="btn" :disabled="busy" @click="act(reviewPathwayKnowledge, i, '已审核')">审核</button>
        </div>
        <div v-if="!inReview.length" class="board-empty">空</div>
      </div>
      <div class="board-col">
        <h3>待审批（{{ approved.length }}）</h3>
        <div v-for="i in approved" :key="i.pathway_version_id" class="board-card">
          <b>{{ i.display_name }}</b>
          <p>v{{ i.version_no }} · {{ i.diagnosis_code }}</p>
          <button class="btn primary" :disabled="busy" @click="act(approvePathwayKnowledge, i, '已审批发布')">审批发布</button>
        </div>
        <div v-if="!approved.length" class="board-empty">空</div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.board { display: flex; gap: 16px; align-items: flex-start; }
.board-col { flex: 1; min-width: 220px; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; max-height: calc(100vh - 180px); overflow-y: auto; }
.board-col h3 { font-size: 13px; margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px solid #e2e8f0; }
.board-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px; margin-bottom: 8px; font-size: 12px; }
.board-card p { margin: 4px 0 8px; color: #64748b; }
.board-empty { color: #cbd5e1; text-align: center; padding: 16px; font-size: 12px; }
.notice-banner { margin: 12px 0; padding: 10px 14px; border-radius: 8px; background: #eff6ff; color: #2563eb; }
.btn { padding: 7px 12px; border-radius: 8px; border: 1px solid #cbd7e5; background: #fff; cursor: pointer; font-size: 12px; }
.btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
@media (max-width: 600px) { .board { display: grid; grid-template-columns: 1fr; } .board-col { min-width: 0; max-height: none; } }
</style>
