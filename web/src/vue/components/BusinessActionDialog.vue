<script setup lang="ts">
import { nextTick, ref, watch } from 'vue';

const props = withDefaults(defineProps<{
  open: boolean;
  title: string;
  eyebrow?: string;
  description?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  busy?: boolean;
  confirmDisabled?: boolean;
  danger?: boolean;
  width?: 'compact' | 'wide';
}>(), {
  description: '',
  eyebrow: '业务配置',
  confirmLabel: '确认',
  cancelLabel: '取消',
  busy: false,
  confirmDisabled: false,
  danger: false,
  width: 'compact',
});

const emit = defineEmits<{ confirm: []; cancel: [] }>();
const dialog = ref<HTMLDialogElement | null>(null);

watch(() => props.open, async (open) => {
  await nextTick();
  const element = dialog.value;
  if (!element) return;
  if (open && !element.open) element.showModal();
  if (!open && element.open) element.close();
}, { immediate: true });

function cancel() {
  if (!props.busy) emit('cancel');
}
</script>

<template>
  <dialog
    ref="dialog"
    class="business-dialog"
    :class="[`business-dialog--${width}`, { 'business-dialog--danger': danger }]"
    :aria-labelledby="`business-dialog-title-${title}`"
    @cancel.prevent="cancel"
    @close="cancel"
  >
    <form method="dialog" class="business-dialog__surface" @submit.prevent="$emit('confirm')">
      <header>
        <div>
          <p class="business-dialog__eyebrow">{{ eyebrow }}</p>
          <h2 :id="`business-dialog-title-${title}`">{{ title }}</h2>
          <p v-if="description">{{ description }}</p>
        </div>
        <button type="button" class="business-dialog__close" aria-label="关闭弹窗" :disabled="busy" @click="cancel">×</button>
      </header>
      <section class="business-dialog__body">
        <slot />
      </section>
      <footer>
        <slot name="leading-actions" />
        <span class="business-dialog__spacer" />
        <button type="button" class="btn" :disabled="busy" @click="cancel">{{ cancelLabel }}</button>
        <button type="submit" class="btn primary" :class="{ danger }" :disabled="busy || confirmDisabled">
          {{ busy ? '处理中…' : confirmLabel }}
        </button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.business-dialog{width:min(560px,calc(100vw - 32px));max-height:calc(100vh - 48px);padding:0;border:0;border-radius:14px;background:transparent;box-shadow:0 24px 70px rgba(20,42,64,.28)}
.business-dialog--wide{width:min(760px,calc(100vw - 32px))}
.business-dialog::backdrop{background:rgba(12,29,45,.48);backdrop-filter:blur(2px)}
.business-dialog__surface{display:grid;max-height:calc(100vh - 48px);grid-template-rows:auto minmax(0,1fr) auto;overflow:hidden;border:1px solid #d8e2ea;border-radius:14px;background:#fff;color:#17283a}
.business-dialog header{display:flex;justify-content:space-between;gap:20px;padding:20px 22px 16px;border-bottom:1px solid #e4ebf0;background:#f8fafc}
.business-dialog h2{margin:2px 0 0;font-size:19px}.business-dialog header p:not(.business-dialog__eyebrow){margin:7px 0 0;color:#657487;font-size:12px;line-height:1.6}
.business-dialog__eyebrow{margin:0;color:#1769aa;font-size:10px;font-weight:700;letter-spacing:.08em;text-transform:uppercase}
.business-dialog__close{width:34px;height:34px;border:1px solid #d6e0e7;border-radius:8px;background:#fff;color:#667587;font-size:22px;line-height:1;cursor:pointer}
.business-dialog__body{display:grid;gap:14px;overflow:auto;padding:20px 22px}.business-dialog__body :deep(label){display:grid;gap:6px;color:#536477;font-size:11px;font-weight:600}.business-dialog__body :deep(input),.business-dialog__body :deep(select),.business-dialog__body :deep(textarea){box-sizing:border-box;width:100%;min-height:40px;padding:9px 11px;border:1px solid #cbd7e0;border-radius:7px;background:#fff;color:#17283a;font:inherit}.business-dialog__body :deep(textarea){min-height:84px;resize:vertical}.business-dialog__body :deep(.dialog-grid){display:grid;grid-template-columns:1fr 1fr;gap:14px}.business-dialog__body :deep(.dialog-check){display:flex;align-items:center;gap:8px}.business-dialog__body :deep(.dialog-check input){width:16px;min-height:auto}.business-dialog__body :deep(.dialog-warning){margin:0;padding:12px 14px;border:1px solid #efd5a4;border-radius:8px;background:#fff8e8;color:#795d1d;font-size:12px;line-height:1.6}.business-dialog--danger .business-dialog__eyebrow{color:#a43131}.business-dialog--danger .business-dialog__body :deep(.dialog-warning){border-color:#efbcbc;background:#fff1f1;color:#8d2c2c}
.business-dialog footer{display:flex;align-items:center;gap:10px;padding:14px 22px;border-top:1px solid #e4ebf0;background:#fff}.business-dialog__spacer{flex:1}.business-dialog footer .btn{min-width:88px}.business-dialog footer .danger{border-color:#b73333;background:#b73333;color:#fff}
@media(max-width:620px){.business-dialog__body :deep(.dialog-grid){grid-template-columns:1fr}.business-dialog header,.business-dialog__body,.business-dialog footer{padding-left:16px;padding-right:16px}}
</style>
