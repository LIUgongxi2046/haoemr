<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import BusinessActionDialog from './BusinessActionDialog.vue';
import { undoAgentAdoption, type AgentAdoptionSnapshot } from '../agent-adoption-undo';
const props = defineProps<{ snapshot: AgentAdoptionSnapshot | null; documentId: string; sections: Record<string, unknown>; labels: Record<string, string>; disabled: boolean }>();
const emit = defineEmits<{ undo: [sections: Record<string, unknown>, allRestored: boolean] }>();
const open = ref(false); const restoreConflicts = ref<string[]>([]); const reviewed = ref(''); const notice = ref('');
const preview = computed(() => props.snapshot && props.snapshot.documentId === props.documentId ? undoAgentAdoption(props.snapshot, props.documentId, props.sections) : null);
watch(() => props.snapshot, () => { open.value = false; restoreConflicts.value = []; notice.value = ''; });
function begin() { reviewed.value = JSON.stringify(props.sections); restoreConflicts.value = []; notice.value = ''; open.value = true; }
function confirm() {
  if (!props.snapshot || props.disabled) return;
  if (JSON.stringify(props.sections) !== reviewed.value) { reviewed.value = JSON.stringify(props.sections); restoreConflicts.value = []; notice.value = '编辑内容已经变化，请重新核对本次比较。'; return; }
  const result = undoAgentAdoption(props.snapshot, props.documentId, props.sections, restoreConflicts.value);
  emit('undo', result.sections, result.restored.length === Object.keys(props.snapshot.adopted).length); open.value = false;
}
</script>
<template>
  <div v-if="preview" class="notice info"><span>可撤销最近一次医助采用；已保存的来源与历史版本继续保留。</span> <button class="btn" :disabled="disabled" @click="begin">撤销最近一次采用</button></div>
  <BusinessActionDialog :open="open" title="撤销本次医助采用" eyebrow="医生确认" description="未再修改的采用字段恢复到采用前；有后续修改的字段默认保留当前内容，请逐项核对。撤销结果仍须保存。" confirm-label="确认撤销到编辑区" :confirm-disabled="disabled" @confirm="confirm" @cancel="open = false">
    <p v-if="notice" role="alert">{{ notice }}</p>
    <p v-if="!preview?.conflicts.length">所采用字段没有后续修改，可以恢复采用前内容。</p>
    <div v-for="field in preview?.conflicts" :key="field"><b>{{ labels[field] ?? field }}：医生已继续编辑</b><p>采用前：{{ snapshot?.before[field]?.value ?? '未填写' }}</p><p>采用内容：{{ snapshot?.adopted[field] }}</p><p>当前内容：{{ sections[field] ?? '未填写' }}</p><label class="dialog-check"><input v-model="restoreConflicts" type="checkbox" :value="field">明确恢复此字段到采用前内容（不勾选则保留当前内容）</label></div>
  </BusinessActionDialog>
</template>
