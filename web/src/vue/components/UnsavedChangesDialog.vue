<script setup lang="ts">
import BusinessActionDialog from './BusinessActionDialog.vue';
defineProps<{ open: boolean; busy: boolean; message: string }>();
defineEmits<{ save: []; discard: []; stay: [] }>();
</script>
<template>
  <BusinessActionDialog :open="open" title="当前病历还有未完成的修改" eyebrow="离开编辑前确认" description="保存成功后再离开；放弃只清除本次未保存修改，已保存的历史版本仍保留。" confirm-label="保存后离开" cancel-label="留在当前病历" :busy="busy" @confirm="$emit('save')" @cancel="$emit('stay')">
    <p v-if="message" role="alert">{{ message }}</p><p v-else>请确认如何处理当前修改。</p>
    <template #leading-actions><button type="button" class="btn" :disabled="busy" @click="$emit('discard')">放弃修改并离开</button></template>
  </BusinessActionDialog>
</template>
