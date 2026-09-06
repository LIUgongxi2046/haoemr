<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { renderEvaMarkdown } from '../eva-markdown';

const props = withDefaults(defineProps<{ text: string; processing?: boolean; phase?: string }>(), { processing: false, phase: '' });
const html = computed(() => renderEvaMarkdown(props.text));
const elapsed = ref(0);
let timer: ReturnType<typeof setInterval> | undefined;
function stopTimer() { if (timer !== undefined) clearInterval(timer); timer = undefined; }
watch(() => props.processing, active => {
  stopTimer();
  if (!active) return;
  const started = Date.now();
  elapsed.value = 0;
  timer = setInterval(() => { elapsed.value = Math.floor((Date.now() - started) / 1000); }, 1000);
}, { immediate: true });
onBeforeUnmount(stopTimer);
</script>

<template>
  <section v-if="processing" class="eva-processing" aria-busy="true" aria-label="EVA 正在处理">
    <div class="eva-processing-orbit" aria-hidden="true"><img class="eva-avatar-motion" data-active="true" src="/brand/ai-medical-assistant-eva.png" alt="" width="26" height="26" /></div>
    <div class="eva-processing-copy">
      <div class="eva-processing-heading"><strong role="status">EVA 正在处理<span class="eva-processing-dots" aria-hidden="true"><i></i><i></i><i></i></span></strong><small aria-hidden="true">已等待 {{ elapsed }} 秒</small></div>
      <p>{{ phase ? `最新进展：${phase}` : '正在提交任务，连接所需的诊疗信息' }}</p>
      <small v-if="elapsed >= 30">分析仍在进行，完成后将在这里显示结果。</small>
    </div>
  </section>
  <div v-else-if="text" class="eva-markdown" v-html="html"></div>
</template>

<style scoped>
.eva-processing { display: flex; align-items: center; gap: 14px; min-width: 0; padding: 17px; border: 1px solid #dae8fa; border-radius: 12px; background: linear-gradient(110deg, #f0f7ff, #f6fbfa); }
.eva-processing-orbit { position: relative; display: grid; place-items: center; flex: 0 0 38px; height: 38px; color: #246bd1; font-size: 21px; }
.eva-processing-orbit::before { content: ''; position: absolute; inset: 0; border: 2px solid #d5e5fb; border-top-color: #2874dd; border-right-color: #65b9ac; border-radius: 50%; animation: eva-orbit 1.6s linear infinite; }
.eva-processing-orbit img { width: 26px; height: 26px; border-radius: 50%; object-fit: cover; }
.eva-processing-copy { flex: 1; min-width: 0; }
.eva-processing-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 5px 12px; }
.eva-processing-heading strong { display: flex; align-items: center; gap: 7px; color: #205a97; font-size: 14px; }
.eva-processing-copy small { color: #748599; font-size: 11px; }
.eva-processing-copy p { margin: 6px 0 0; color: #62778d; font-size: 12px; line-height: 1.65; overflow-wrap: anywhere; }
.eva-processing-dots { display: inline-flex; gap: 3px; }
.eva-processing-dots i { width: 3px; height: 3px; border-radius: 50%; background: #3276d4; animation: eva-breathe 1.2s ease-in-out infinite; }
.eva-processing-dots i:nth-child(2) { animation-delay: .2s; }.eva-processing-dots i:nth-child(3) { animation-delay: .4s; }
@keyframes eva-orbit { to { transform: rotate(360deg); } }
@keyframes eva-breathe { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .eva-processing-orbit::before, .eva-processing-orbit span, .eva-processing-dots i { animation: none; } }
.eva-markdown { min-width: 0; max-width: 100%; color: #334b62; font-size: 14px; line-height: 1.85; overflow-wrap: anywhere; white-space: normal; }
.eva-markdown :deep(> :first-child) { margin-top: 0; }.eva-markdown :deep(> :last-child) { margin-bottom: 0; }
.eva-markdown :deep(p) { margin: 0 0 12px; }
.eva-markdown :deep(h1), .eva-markdown :deep(h2), .eva-markdown :deep(h3), .eva-markdown :deep(h4), .eva-markdown :deep(h5), .eva-markdown :deep(h6) { margin: 22px 0 10px; color: #203d59; font-weight: 700; line-height: 1.5; }
.eva-markdown :deep(h1), .eva-markdown :deep(h2) { font-size: 18px; padding-bottom: 8px; border-bottom: 1px solid #e6edf5; }
.eva-markdown :deep(h3) { font-size: 16px; }.eva-markdown :deep(h4), .eva-markdown :deep(h5), .eva-markdown :deep(h6) { font-size: 14px; }
.eva-markdown :deep(ul), .eva-markdown :deep(ol) { display: block; margin: 8px 0 16px; padding-left: 24px; border: 0; background: none; list-style: revert; }
.eva-markdown :deep(li) { display: list-item; margin: 6px 0; padding: 0 0 0 3px; background: none; }
.eva-markdown :deep(li > p) { margin: 5px 0; }.eva-markdown :deep(li::marker) { color: #4a7db3; }
.eva-markdown :deep(strong) { color: #1c466e; font-weight: 700; }
.eva-markdown :deep(blockquote) { margin: 16px 0; padding: 12px 15px; border-left: 3px solid #7aa9dc; border-radius: 0 8px 8px 0; color: #566d83; background: #f3f7fc; }
.eva-markdown :deep(blockquote p:last-child) { margin-bottom: 0; }
.eva-markdown :deep(a) { color: #1769c9; text-decoration: underline; text-underline-offset: 3px; }
.eva-markdown :deep(code) { padding: 2px 5px; border-radius: 4px; background: #f0f4f8; font-size: .88em; }
.eva-markdown :deep(pre) { max-width: 100%; overflow-x: auto; margin: 14px 0; padding: 14px; border: 1px solid #e0e8f1; border-radius: 9px; background: #f7f9fc; line-height: 1.65; white-space: pre; }
.eva-markdown :deep(pre code) { padding: 0; background: none; overflow-wrap: normal; }
.eva-markdown :deep(.eva-markdown-table) { max-width: 100%; overflow-x: auto; margin: 16px 0; border: 1px solid #dde7f1; border-radius: 9px; }
.eva-markdown :deep(table) { width: 100%; border-collapse: collapse; font-size: 13px; }
.eva-markdown :deep(th), .eva-markdown :deep(td) { min-width: 100px; padding: 10px 12px; border-bottom: 1px solid #e7edf4; text-align: left; vertical-align: top; }
.eva-markdown :deep(th) { color: #335a80; font-weight: 650; background: #eff5fb; }.eva-markdown :deep(tbody tr:nth-child(even)) { background: #fafcfe; }
.eva-markdown :deep(tr:last-child td) { border-bottom: 0; }.eva-markdown :deep(hr) { margin: 20px 0; border: 0; border-top: 1px solid #e3ebf3; }
</style>
