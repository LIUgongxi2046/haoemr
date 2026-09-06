<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import {
  approvePathwayKnowledge,
  createPathwayKnowledge,
  createPathwayKnowledgeVersion,
  issuePathwayKnowledgeLease,
  listPathwayKnowledge,
  listPathwayKnowledgeVersions,
  retirePathwayKnowledge,
  reviewPathwayKnowledge,
  submitPathwayKnowledge,
} from '../../api/pathway-knowledge';
import type { ContextLeaseWire, PathwayKnowledgeVersionWire, PathwayKnowledgeWire } from '../../generated/contracts';

const lease = ref<ContextLeaseWire | null>(null);
const busy = ref(false);
const notice = ref('');
const search = ref('');
const pathways = ref<PathwayKnowledgeWire[]>([]);
const versions = ref<PathwayKnowledgeVersionWire[]>([]);
const selectedId = ref<string | null>(null);
const showCreateVersion = ref(false);
const showCreatePath = ref(false);
const versionForm = reactive({ stage_name: '第1天', task_content: '' });
const pathwayForm = reactive({ pathway_code: '', display_name: '', specialty_code: '', diagnosis_code: '', inclusion_criteria: '', exclusion_criteria: '', avg_los_days: 7 });

const selected = computed(() => pathways.value.find((p) => p.pathway_knowledge_id === selectedId.value) ?? null);
const activeVersion = computed(() => versions.value.find((v) => v.status === 'ACTIVE')
  ?? versions.value.find((v) => v.status === 'APPROVED')
  ?? versions.value.find((v) => v.status === 'IN_REVIEW')
  ?? versions.value.find((v) => v.status === 'DRAFT')
  ?? null);

const outline = computed(() => activeVersion.value?.stages?.map((s, i) => ({ id: `stage-${i}`, name: s.stage_name })) ?? []);

async function ensureLease(): Promise<ContextLeaseWire> {
  if (!lease.value) lease.value = await issuePathwayKnowledgeLease();
  return lease.value;
}

async function refresh() {
  await run(async () => {
    const l = await ensureLease();
    pathways.value = await listPathwayKnowledge(l);
    if (!selectedId.value && pathways.value.length) {
      await selectPath(pathways.value[0].pathway_knowledge_id);
    }
  });
}

async function selectPath(id: string) {
  selectedId.value = id;
  const l = await ensureLease();
  versions.value = await listPathwayKnowledgeVersions(l, id);
}

async function run<T>(action: () => Promise<T>): Promise<T | undefined> {
  if (busy.value) return undefined;
  busy.value = true;
  notice.value = '';
  try { return await action(); }
  catch (e) { notice.value = e instanceof Error ? e.message : '操作失败'; return undefined; }
  finally { busy.value = false; }
}

async function doCreateVersion() {
  if (!selectedId.value) return;
  const l = await ensureLease();
  const created = await run(() => createPathwayKnowledgeVersion(l, selectedId.value!, {
    stages: [{
      stage_code: 'DAY1', stage_name: versionForm.stage_name || '第1天', sequence_no: 1,
      expected_day_start: 1, expected_day_end: 1, stage_goal: null, assessment_points: null,
      tasks: versionForm.task_content ? [{ task_type: 'MEDICATION', content: versionForm.task_content, code_ref: null, required: true, sequence_no: 1 }] : [],
    }],
    variances: [], quality_points: [],
  }));
  if (created) {
    notice.value = `版本 v${created.version_no} 已创建（草稿）`;
    versionForm.task_content = '';
    showCreateVersion.value = false;
    await selectPath(selectedId.value);
  }
}

async function doCreatePathway() {
  const l = await ensureLease();
  const created = await run(() => createPathwayKnowledge(l, {
    pathway_code: pathwayForm.pathway_code.trim(), display_name: pathwayForm.display_name.trim(),
    specialty_code: pathwayForm.specialty_code.trim(), diagnosis_code: pathwayForm.diagnosis_code.trim(),
    inclusion_criteria: pathwayForm.inclusion_criteria.trim() || null,
    exclusion_criteria: pathwayForm.exclusion_criteria.trim() || null,
    avg_los_days: Number.isInteger(pathwayForm.avg_los_days) ? pathwayForm.avg_los_days : null,
  }));
  if (created) {
    notice.value = `路径“${created.display_name}”已创建，请新增版本后提交独立审核。`;
    Object.assign(pathwayForm, { pathway_code: '', display_name: '', specialty_code: '', diagnosis_code: '', inclusion_criteria: '', exclusion_criteria: '', avg_los_days: 7 });
    showCreatePath.value = false;
    await refresh();
    await selectPath(created.pathway_knowledge_id);
  }
}

async function doAct(fn: (l: ContextLeaseWire, id: string) => Promise<PathwayKnowledgeVersionWire>, versionId: string, label: string) {
  const l = await ensureLease();
  await run(() => fn(l, versionId));
  notice.value = label;
  await selectPath(selectedId.value!);
}

function scrollToStage(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

const statusBadge = (s: string) => ({ DRAFT: '草稿', IN_REVIEW: '待审核', APPROVED: '待审批', ACTIVE: '已发布', RETIRED: '已回退' } as Record<string, string>)[s] ?? s;
const taskTypeLabel = (t: string) => ({ MEDICATION: '医嘱', LAB: '检验', IMAGING: '检查', NURSING: '护理', EDUCATION: '宣教', ASSESSMENT: '评估' } as Record<string, string>)[t] ?? t;

function activeVersionFor(p: PathwayKnowledgeWire): boolean { return p.status === 'ACTIVE'; }

onMounted(() => { void refresh(); });
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <p v-if="notice" class="notice-banner">{{ notice }}</p>
    <div class="ob-workspace">
      <!-- 左栏：文件树 -->
      <aside class="ob-pane ob-left">
        <div class="ob-pane-head">
          <span class="ob-pane-title">路径库</span>
          <button class="ob-btn" title="新建路径" @click="showCreatePath = !showCreatePath">＋</button>
        </div>
        <input v-model="search" class="ob-search" placeholder="搜索路径…" />
        <div class="ob-tree">
          <button
            v-for="p in pathways.filter((x) => x.display_name.toLowerCase().includes(search.toLowerCase()) || x.pathway_code.toLowerCase().includes(search.toLowerCase()))"
            :key="p.pathway_knowledge_id"
            class="ob-file"
            :class="{ active: p.pathway_knowledge_id === selectedId }"
            @click="selectPath(p.pathway_knowledge_id)"
          >
            <span class="ob-file-icon">▤</span>
            <span class="ob-file-name">{{ p.display_name }}</span>
            <span class="ob-badge" :class="activeVersionFor(p) ? 'pub' : 'draft'"></span>
          </button>
        </div>
      </aside>

      <!-- 中栏：Markdown 编辑器/查看 -->
      <section class="ob-pane ob-mid">
        <div class="ob-tabbar">
          <h1 class="ob-tab active">{{ selected?.display_name ?? '知识中心' }}</h1>
          <button class="ob-btn" @click="showCreateVersion = !showCreateVersion">{{ showCreateVersion ? '收起' : '＋ 新建版本' }}</button>
        </div>

        <form v-if="showCreatePath" class="ob-pathway-create" @submit.prevent="doCreatePathway">
          <strong>新建临床路径知识</strong>
          <label>路径编码<input v-model="pathwayForm.pathway_code" required maxlength="128" placeholder="例：E2E-NEPHROPATHY" /></label>
          <label>路径名称<input v-model="pathwayForm.display_name" required maxlength="256" placeholder="例：肾病综合征住院路径" /></label>
          <label>专科编码<input v-model="pathwayForm.specialty_code" required maxlength="128" placeholder="例：NEPHROLOGY" /></label>
          <label>诊断编码<input v-model="pathwayForm.diagnosis_code" required maxlength="128" placeholder="例：N04" /></label>
          <label>纳入标准<textarea v-model="pathwayForm.inclusion_criteria" rows="2" /></label>
          <label>排除标准<textarea v-model="pathwayForm.exclusion_criteria" rows="2" /></label>
          <label>平均住院日<input v-model.number="pathwayForm.avg_los_days" type="number" min="1" max="365" /></label>
          <div><button type="button" class="btn" @click="showCreatePath = false">取消</button><button class="btn primary" :disabled="busy">创建路径知识</button></div>
        </form>

        <div v-if="showCreateVersion" class="ob-create">
          <input v-model="versionForm.stage_name" placeholder="阶段名" />
          <input v-model="versionForm.task_content" placeholder="任务内容" />
          <button class="btn primary" :disabled="busy" @click="doCreateVersion">创建</button>
        </div>

        <article v-if="selected" class="ob-doc">
          <!-- frontmatter 元数据 -->
          <pre class="ob-frontmatter">---
编码: {{ selected.pathway_code }}
诊断: {{ selected.diagnosis_code }}
专科: {{ selected.specialty_code }}
平均住院日: {{ selected.avg_los_days ?? '—' }}
纳入标准: {{ selected.inclusion_criteria ?? '未填写' }}
---</pre>

          <template v-if="activeVersion">
            <div v-for="(stage, i) in activeVersion.stages ?? []" :key="stage.stage_id" :id="`stage-${i}`" class="ob-stage">
              <h2 class="ob-h2">{{ stage.stage_name }}</h2>
              <ul class="ob-tasks">
                <li v-for="task in stage.tasks ?? []" :key="task.task_id" class="ob-task">
                  <span class="ob-checkbox">☐</span>
                  <span class="ob-tag">{{ taskTypeLabel(task.task_type) }}</span>
                  <span>{{ task.content }}</span>
                  <span v-if="task.code_ref" class="ob-link">[[{{ task.code_ref }}]]</span>
                </li>
              </ul>
            </div>

            <template v-if="(activeVersion.variances ?? []).length">
              <h2 class="ob-h2">变异处理规则</h2>
              <ul class="ob-tasks">
                <li v-for="v in activeVersion.variances" :key="v.variance_id" class="ob-task">
                  <span class="ob-checkbox">☐</span><span class="ob-tag">变异</span>
                  <span>{{ v.variance_type }}：{{ v.disposition ?? v.trigger_condition ?? '' }}</span>
                </li>
              </ul>
            </template>

            <template v-if="(activeVersion.quality_points ?? []).length">
              <h2 class="ob-h2">质控要点</h2>
              <ul class="ob-tasks">
                <li v-for="q in activeVersion.quality_points" :key="q.quality_point_id" class="ob-task">
                  <span class="ob-checkbox">☐</span><span class="ob-tag">质控</span>
                  <span>{{ q.indicator }} · {{ q.standard ?? '' }}</span>
                </li>
              </ul>
            </template>
          </template>
          <div v-else class="ob-empty">该路径暂无版本内容</div>
        </article>
        <div v-else class="ob-empty ob-empty-big">
          {{ pathways.length ? '从左侧选择一条临床路径查看其知识内容' : '当前知识库为空，请检查数据库导入或运行环境配置' }}
        </div>
      </section>

      <!-- 路径大纲与版本操作 -->
      <aside class="ob-pane ob-right">
        <div class="ob-pane-head"><span class="ob-pane-title">大纲</span></div>
        <div>
          <button v-for="o in outline" :key="o.id" class="ob-outline-item" @click="scrollToStage(o.id)">{{ o.name }}</button>
          <div v-if="!outline.length" class="ob-empty">无大纲</div>
        </div>
        <div class="ob-actions">
          <div class="ob-backlink-head">版本 · {{ versions.length }}</div>
          <div v-for="(v, i) in versions" :key="v.pathway_version_id" class="ob-tl" :class="{ old: i > 0 }">v{{ v.version_no }} · {{ statusBadge(v.status) }}</div>
          <template v-if="activeVersion">
            <button v-if="activeVersion.status === 'DRAFT'" class="btn" :disabled="busy" @click="doAct(submitPathwayKnowledge, activeVersion.pathway_version_id, '已提交审核')">提交审核</button>
            <button v-if="activeVersion.status === 'IN_REVIEW'" class="btn" :disabled="busy" @click="doAct(reviewPathwayKnowledge, activeVersion.pathway_version_id, '已审核')">审核</button>
            <button v-if="activeVersion.status === 'APPROVED'" class="btn primary" :disabled="busy" @click="doAct(approvePathwayKnowledge, activeVersion.pathway_version_id, '已审批发布')">审批发布</button>
            <button v-if="activeVersion.status === 'ACTIVE'" class="btn danger" :disabled="busy" @click="doAct(retirePathwayKnowledge, activeVersion.pathway_version_id, '已回退')">回退</button>
          </template>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.ob-workspace { display: flex; height: calc(100vh - 130px); min-height: 0; }
.ob-pane { display: flex; flex-direction: column; background: #fff; overflow-y: auto; }
.ob-left { width: 250px; flex-shrink: 0; border-right: 1px solid #e2e8f0; }
.ob-mid { flex: 1; min-width: 0; padding: 0 20px; }
.ob-right { width: 250px; flex-shrink: 0; border-left: 1px solid #e2e8f0; padding: 0 12px; }
.ob-pane-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 12px 8px; }
.ob-pane-title { font-size: 12px; font-weight: 700; color: #64748b; letter-spacing: .03em; }
.ob-btn { border: none; background: transparent; cursor: pointer; font-size: 14px; color: #64748b; }
.ob-btn:hover { color: #2563eb; }
.ob-search { margin: 0 12px 8px; padding: 7px 10px; border: 1px solid #e2e8f0; border-radius: 6px; font-size: 12px; }
.ob-tree { display: flex; flex-direction: column; }
.ob-file { display: flex; align-items: center; gap: 6px; padding: 5px 12px; border: none; background: transparent; cursor: pointer; font-size: 13px; text-align: left; }
.ob-file:hover { background: #f1f5f9; }
.ob-file.active { background: #eff6ff; color: #2563eb; }
.ob-file-icon { color: #94a3b8; font-size: 12px; }
.ob-file-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ob-badge { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.ob-badge.pub { background: #10b981; }
.ob-badge.draft { background: #f59e0b; }
.ob-tabbar { display: flex; align-items: center; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #e2e8f0; margin-bottom: 10px; }
.ob-tab { border: none; background: transparent; cursor: pointer; font-size: 12px; padding: 5px 10px; border-radius: 6px; color: #64748b; }
.ob-tab.active { background: #eff6ff; color: #2563eb; font-weight: 600; }
.ob-create { display: flex; gap: 8px; padding: 10px 0; }
.ob-create input { flex: 1; min-width: 0; padding: 7px 10px; border: 1px solid #e2e8f0; border-radius: 6px; font-size: 13px; }
.ob-pathway-create { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 10px 0; padding: 12px; border: 1px solid #bfdbfe; border-radius: 8px; background: #f8fbff; }
.ob-pathway-create strong, .ob-pathway-create label:nth-child(5), .ob-pathway-create label:nth-child(6), .ob-pathway-create label:nth-child(7), .ob-pathway-create > div { grid-column: 1 / -1; }
.ob-pathway-create label { display: grid; gap: 4px; font-size: 12px; color: #475569; }
.ob-pathway-create input, .ob-pathway-create textarea { box-sizing: border-box; width: 100%; padding: 7px 10px; border: 1px solid #cbd7e5; border-radius: 6px; font: inherit; }
.ob-doc { padding: 6px 0 20px; }
.ob-frontmatter { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 14px; font-size: 12px; color: #475569; margin-bottom: 16px; white-space: pre-wrap; font-family: inherit; }
.ob-h2 { font-size: 17px; margin: 18px 0 8px; padding-bottom: 4px; border-bottom: 1px solid #f1f5f9; }
.ob-tasks { list-style: none; margin: 0; padding: 0; }
.ob-task { display: flex; align-items: flex-start; gap: 8px; padding: 5px 0; font-size: 13px; line-height: 1.5; }
.ob-checkbox { color: #94a3b8; }
.ob-tag { font-size: 10px; padding: 1px 6px; border-radius: 4px; background: #f1f5f9; color: #64748b; flex-shrink: 0; }
.ob-link { color: #7c3aed; cursor: pointer; font-family: inherit; }
.ob-link:hover { text-decoration: underline; }
.ob-empty { color: #94a3b8; padding: 16px; font-size: 12px; text-align: center; }
.ob-empty-big { display: flex; align-items: center; justify-content: center; height: 200px; }
.ob-outline-item { display: block; width: 100%; text-align: left; padding: 4px 8px; border: none; background: transparent; cursor: pointer; font-size: 12px; color: #475569; border-radius: 5px; }
.ob-outline-item:hover { background: #f1f5f9; }
.ob-backlink-head { font-size: 11px; color: #64748b; font-weight: 700; margin: 12px 0 6px; }
.ob-backlink { display: block; width: 100%; text-align: left; padding: 4px 8px; border: none; background: #f8fafc; cursor: pointer; font-size: 12px; color: #7c3aed; border-radius: 5px; margin-bottom: 3px; }
.ob-backlink:hover { background: #eff6ff; }
.ob-actions { margin-top: 16px; border-top: 1px solid #f1f5f9; padding-top: 8px; }
.ob-tl { font-size: 11px; color: #64748b; padding: 2px 0; }
.ob-tl.old { color: #cbd5e1; }
.btn { padding: 7px 12px; border-radius: 6px; border: 1px solid #cbd7e5; background: #fff; cursor: pointer; font-size: 12px; margin: 4px 4px 0 0; }
.btn.primary { background: #2563eb; color: #fff; border-color: #2563eb; }
.btn.danger { background: #fee2e2; color: #dc2626; border-color: #fecaca; }
.notice-banner { margin: 12px; padding: 10px 14px; border-radius: 8px; background: #eff6ff; color: #2563eb; }
@media (max-width: 600px) { .ob-workspace { height: auto; flex-direction: column; } .ob-left, .ob-right { width: auto; max-height: 180px; border: 1px solid #e2e8f0; border-radius: 10px; } .ob-mid { min-width: 0; padding: 0; } .ob-pathway-create { grid-template-columns: minmax(0, 1fr); } .ob-pathway-create strong, .ob-pathway-create label:nth-child(5), .ob-pathway-create label:nth-child(6), .ob-pathway-create label:nth-child(7), .ob-pathway-create > div { grid-column: auto; } }
</style>
