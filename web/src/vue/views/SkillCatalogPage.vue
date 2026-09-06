<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query';
import { computed, reactive, ref } from 'vue';
import type { SkillRegistryWire } from '../../generated/contracts';
import { deactivateSkill, issueAiLease, listSkills, publishSkillVersion, registerSkill } from '../../api/ai-platform';
import AdminActionDialog from '../components/AdminActionDialog.vue';
import AdminConfirmDialog from '../components/AdminConfirmDialog.vue';
import ClinicalPageState from '../components/ClinicalPageState.vue';
import { toClinicalIssue } from '../clinical-error';

const leaseQuery = useQuery({
  queryKey: ['ai-platform', 'skill-catalog', 'lease'],
  queryFn: () => issueAiLease('AI_PLATFORM_ADMIN'),
  retry: false, staleTime: 5 * 60_000, gcTime: 0,
});
const skillsQuery = useQuery({
  queryKey: ['ai-platform', 'skill-catalog', 'skills'],
  queryFn: () => listSkills(leaseQuery.data.value!),
  enabled: () => Boolean(leaseQuery.data.value),
  retry: false,
});
const issue = computed(() => (leaseQuery.error.value ?? skillsQuery.error.value)
  ? toClinicalIssue(leaseQuery.error.value ?? skillsQuery.error.value) : null);
const skills = computed(() => skillsQuery.data.value ?? []);
const activeCount = computed(() => skills.value.filter((skill) => skill.status === 'ACTIVE').length);

const form = reactive({ skillCode: '', skillName: '', skillVersion: '' });
const editingSkill = ref<SkillRegistryWire | null>(null);
const editorOpen = ref(false);
const deactivateTarget = ref<SkillRegistryWire | null>(null);
const busy = ref('');
const notice = ref('');

async function reload() {
  notice.value = '';
  await skillsQuery.refetch();
}

function nextVersion(version: string) {
  const match = version.match(/^(?:v)?(\d+)\.(\d+)\.(\d+)$/);
  return match ? `${match[1]}.${match[2]}.${Number(match[3]) + 1}` : `${version}-next`;
}

function resetForm() {
  editingSkill.value = null;
  form.skillCode = ''; form.skillName = ''; form.skillVersion = '';
}

function openCreate() {
  resetForm();
  editorOpen.value = true;
}

function editSkill(skill: SkillRegistryWire) {
  editingSkill.value = skill;
  form.skillCode = skill.skill_code; form.skillName = skill.skill_name;
  form.skillVersion = nextVersion(skill.skill_version); notice.value = '';
  editorOpen.value = true;
}

async function register() {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || !form.skillCode.trim() || !form.skillName.trim() || !form.skillVersion.trim()) return;
  busy.value = 'create'; notice.value = '';
  try {
    if (editingSkill.value) {
      await publishSkillVersion(lease, editingSkill.value, {
        skill_name: form.skillName.trim(), skill_version: form.skillVersion.trim(),
      });
      notice.value = '新能力版本已发布，旧版本已自动停用；依赖该能力的后续任务将使用新版本。';
    } else {
      await registerSkill(lease, {
        skill_code: form.skillCode.trim(), skill_name: form.skillName.trim(), skill_version: form.skillVersion.trim(),
      });
      notice.value = '医助skill已登记，版本记录和操作留痕已同步更新。';
    }
    resetForm();
    editorOpen.value = false;
    await skillsQuery.refetch();
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}

async function deactivate(skill: SkillRegistryWire) {
  const lease = leaseQuery.data.value;
  if (!lease || busy.value || skill.status !== 'ACTIVE') return;
  busy.value = skill.skill_registry_id; notice.value = '';
  try {
    await deactivateSkill(lease, skill);
    notice.value = `医助skill“${skill.skill_name}”已停用。`;
    await skillsQuery.refetch();
    deactivateTarget.value = null;
  } catch (error) {
    const next = toClinicalIssue(error); notice.value = `${next.code}：${next.message}`;
  } finally { busy.value = ''; }
}
</script>

<template>
  <section data-page-root class="content admin-content vue-native-page">
    <div class="page-heading admin-heading">
      <div>
        <p class="eyebrow">AI中心 / 医助skill管理</p>
        <h1>医助skill库</h1>
        <p>管理病历整理、证据核验、风险提示等可复用能力；所有变更保留版本和操作记录，停用后历史任务仍可追溯。</p>
      </div>
    </div>

    <ClinicalPageState v-if="leaseQuery.isPending.value || skillsQuery.isPending.value" kind="loading" message="正在读取医助skill库" />
    <ClinicalPageState v-else-if="issue" kind="error" :code="issue.code" :message="issue.message" @retry="reload" />

    <template v-else>
      <section class="admin-metrics" aria-label="医助skill统计">
        <article><span>能力总数</span><strong>{{ skills.length }}</strong><small>全部登记</small></article>
        <article><span>可用能力</span><strong>{{ activeCount }}</strong><small>当前已启用</small></article>
        <article><span>版本管理</span><strong>全量</strong><small>新版本生效、旧版本留痕</small></article>
        <article><span>使用方式</span><strong>按团队编排</strong><small>随医助任务调用</small></article>
      </section>
      <p v-if="notice" class="admin-notice" role="status">{{ notice }}</p>

      <div>
        <section class="admin-panel">
          <header>
            <div><h2>医助skill版本台账</h2><p>编码与版本不可变；停用后保留历史记录。</p></div>
            <div class="admin-row-actions"><button class="button secondary" @click="skillsQuery.refetch()">刷新</button><button class="button primary" @click="openCreate">新建医助skill</button></div>
          </header>
          <div v-if="skills.length === 0" class="admin-empty" role="status">暂无医助skill，请点击“新建医助skill”。</div>
          <div v-else class="admin-table-wrap">
            <table class="admin-table">
              <thead><tr><th>名称</th><th>编码</th><th>版本</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="skill in skills" :key="skill.skill_registry_id">
                  <td><strong>{{ skill.skill_name }}</strong><small>…{{ skill.skill_registry_id.slice(-8) }}</small></td>
                  <td><code>{{ skill.skill_code }}</code></td>
                  <td><code>{{ skill.skill_version }}</code></td>
                  <td><span class="admin-status" :class="skill.status.toLowerCase()">{{ skill.status === 'ACTIVE' ? '有效' : '已停用' }}</span></td>
                  <td><div class="admin-row-actions"><button class="task-action" :disabled="skill.status !== 'ACTIVE' || Boolean(busy)" @click="editSkill(skill)">编辑</button><button class="task-action danger" :disabled="skill.status !== 'ACTIVE' || Boolean(busy)" @click="deactivateTarget = skill">删除</button></div></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

      </div>

      <AdminActionDialog v-model:open="editorOpen" :title="editingSkill ? '编辑并发布能力新版本' : '新建医助skill'" :description="editingSkill ? '编码保持不变；新版本生效后，旧版本保留在历史任务中。' : '登记后可供医助agent编排和调用。'" size="large" :busy="Boolean(busy)" @update:open="!$event && resetForm()">
          <form class="admin-form ai-center-dialog-form" @submit.prevent="register">
            <label><span>能力编码</span><input v-model="form.skillCode" maxlength="128" required :disabled="Boolean(editingSkill)" placeholder="例：MEDICAL-NOTE" /></label>
            <label><span>能力名称</span><input v-model="form.skillName" maxlength="256" required placeholder="例：门诊病历摘要" /></label>
            <label><span>版本</span><input v-model="form.skillVersion" maxlength="64" required placeholder="例：1.0.0" /></label>
            <div class="admin-form-actions"><button class="button secondary" type="button" :disabled="Boolean(busy)" @click="editorOpen = false">取消</button><button class="button primary" :disabled="Boolean(busy)">{{ busy === 'create' ? '正在保存…' : editingSkill ? '发布新版本' : '登记并生效' }}</button></div>
          </form>
      </AdminActionDialog>
      <AdminConfirmDialog :open="Boolean(deactivateTarget)" :title="`删除医助skill ${deactivateTarget?.skill_name ?? ''}`" description="删除将以安全停用方式执行；新任务不再使用该能力，历史任务和审计记录继续保留。" confirm-label="确认删除并停用" :busy="Boolean(busy)" @update:open="!$event && (deactivateTarget = null)" @confirm="deactivateTarget && deactivate(deactivateTarget)"><div v-if="deactivateTarget" class="admin-impact-grid"><div><span>能力编码</span><b>{{ deactivateTarget.skill_code }}</b></div><div><span>当前版本</span><b>{{ deactivateTarget.skill_version }}</b></div><div><span>当前状态</span><b>有效</b></div><div><span>流程影响</span><b>退出新任务能力编排</b></div></div></AdminConfirmDialog>
    </template>
  </section>
</template>
