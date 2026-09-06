<script setup lang="ts">
import { onMounted, ref } from 'vue';
import {
  issuePathwayKnowledgeLease,
  listPathwayKnowledge,
  listPathwayKnowledgeVersions,
  retirePathwayKnowledge,
} from '../../api/pathway-knowledge';
import type { ContextLeaseWire, PathwayKnowledgeVersionWire, PathwayKnowledgeWire } from '../../generated/contracts';

const lease = ref<ContextLeaseWire | null>(null);
const busy = ref(false);
const notice = ref('');
const pathways = ref<PathwayKnowledgeWire[]>([]);
const versions = ref<PathwayKnowledgeVersionWire[]>([]);
const selectedId = ref<string | null>(null);
const selectedName = ref('');

async function ensureLease(): Promise<ContextLeaseWire> {
  if (!lease.value) lease.value = await issuePathwayKnowledgeLease();
  return lease.value;
}

async function refresh() {
  const l = await ensureLease();
  pathways.value = await listPathwayKnowledge(l);
  if (!selectedId.value && pathways.value.length) {
    await select(pathways.value[0].pathway_knowledge_id);
  }
}

async function select(id: string) {
  selectedId.value = id;
  selectedName.value = pathways.value.find((p) => p.pathway_knowledge_id === id)?.display_name ?? '';
  const l = await ensureLease();
  versions.value = await listPathwayKnowledgeVersions(l, id);
}

async function doRetire(versionId: string) {
  const l = await ensureLease();
  busy.value = true;
  notice.value = '';
  try {
    await retirePathwayKnowledge(l, versionId);
    notice.value = '已回退（退役执行配置）';
    await select(selectedId.value!);
  } catch (e) {
    notice.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busy.value = false;
  }
}

const statusLabel = (s: string) => ({ DRAFT: '草稿', IN_REVIEW: '待审核', APPROVED: '待审批', ACTIVE: '已发布', RETIRED: '已回退' } as Record<string, string>)[s] ?? s;

onMounted(() => { void refresh(); });
</script>

<template>
  <section data-page-root class="content vue-native-page">
    <div class="page-head">
      <div class="page-title">
        <h1>版本历史</h1>
        <p>查看临床路径的版本时间线，回退已发布版本</p>
      </div>
    </div>
    <p v-if="notice" class="notice-banner">{{ notice }}</p>

    <div class="ver-layout">
      <aside class="ver-side">
        <div class="ver-label">选择路径</div>
        <button
          v-for="p in pathways" :key="p.pathway_knowledge_id"
          class="ver-item" :class="{ active: p.pathway_knowledge_id === selectedId }"
          @click="select(p.pathway_knowledge_id)"
        >{{ p.display_name }}</button>
      </aside>

      <section class="ver-main">
        <template v-if="selectedId">
          <h2>{{ selectedName }} · 版本时间线</h2>
          <div class="timeline">
            <div v-for="(v, i) in versions" :key="v.pathway_version_id" class="tl-entry" :class="{ latest: i === 0 }">
              <div class="tl-marker"></div>
              <div class="tl-body">
                <div class="tl-head">
                  <b>v{{ v.version_no }} · {{ statusLabel(v.status) }}</b>
                  <span class="tl-hash">{{ (v.content_hash ?? '').slice(0, 12) }}</span>
                </div>
                <p>{{ (v.stages ?? []).length }} 阶段 · {{ (v.variances ?? []).length }} 变异 · {{ (v.quality_points ?? []).length }} 质控点</p>
                <button v-if="v.status === 'ACTIVE'" class="btn danger" :disabled="busy" @click="doRetire(v.pathway_version_id)">回退此版本</button>
              </div>
            </div>
            <div v-if="!versions.length" class="ver-empty">暂无版本</div>
          </div>
        </template>
        <div v-else class="ver-empty ver-empty-big">从左侧选择路径查看版本历史</div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.ver-layout { display: flex; gap: 16px; }
.ver-side { width: 260px; flex-shrink: 0; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; max-height: 60vh; overflow-y: auto; }
.ver-label { font-size: 12px; color: #64748b; font-weight: 600; margin-bottom: 8px; }
.ver-item { display: block; width: 100%; text-align: left; padding: 8px 10px; border: none; background: transparent; border-radius: 8px; cursor: pointer; font-size: 13px; }
.ver-item:hover { background: #f1f5f9; }
.ver-item.active { background: #eff6ff; color: #2563eb; }
.ver-main { flex: 1; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 18px; }
.ver-main h2 { font-size: 16px; margin-bottom: 16px; }
.timeline { border-left: 2px solid #e2e8f0; margin-left: 6px; }
.tl-entry { position: relative; padding: 0 0 20px 20px; }
.tl-marker { position: absolute; left: -8px; top: 4px; width: 14px; height: 14px; border-radius: 50%; background: #3b82f6; border: 2px solid #fff; }
.tl-entry.latest .tl-marker { background: #10b981; }
.tl-body { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; }
.tl-head { display: flex; justify-content: space-between; align-items: center; font-size: 13px; }
.tl-hash { font-size: 11px; color: #94a3b8; font-family: monospace; }
.tl-body p { margin: 4px 0 8px; color: #64748b; font-size: 12px; }
.ver-empty { color: #94a3b8; padding: 16px; font-size: 13px; text-align: center; }
.ver-empty-big { display: flex; align-items: center; justify-content: center; height: 200px; }
.notice-banner { margin: 12px 0; padding: 10px 14px; border-radius: 8px; background: #eff6ff; color: #2563eb; }
.btn { padding: 7px 12px; border-radius: 8px; border: 1px solid #cbd7e5; background: #fff; cursor: pointer; font-size: 12px; }
.btn.danger { background: #fee2e2; color: #dc2626; border-color: #fecaca; }
@media (max-width: 600px) { .ver-layout { display: grid; grid-template-columns: 1fr; } .ver-side { width: auto; max-height: 180px; } .ver-main { min-width: 0; padding: 14px; } .tl-head { align-items: flex-start; flex-direction: column; gap: 4px; } }
</style>
