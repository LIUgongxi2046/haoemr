<script setup lang="ts">
import { computed } from 'vue';
import { RouterLink } from 'vue-router';
import type { PendingResult } from '../pending-work';

const props = defineProps<{ groups: Array<{ id: string; label: string; rows: PendingResult[] }> }>();
defineEmits<{ close: []; refresh: [] }>();
const visibleGroups = computed(() => props.groups.map(group => ({
  ...group, rows: group.rows.filter(row => row.state !== 'ready' || (row.count ?? 0) > 0),
})).filter(group => group.rows.length));
</script>

<template>
  <section id="topbar-pending-work" class="topbar-popover pending-popover" role="region" aria-label="统一待办">
    <header><strong>待办</strong><div><button class="btn sm" type="button" @click="$emit('refresh')">刷新</button><button class="btn sm" type="button" aria-label="关闭待办" @click="$emit('close')">关闭</button></div></header>
    <div class="pending-popover-body">
      <p v-if="!visibleGroups.length" class="pending-empty">当前统计范围暂无待办</p>
      <section v-for="group in visibleGroups" :key="group.id" class="pending-group">
        <h2>{{ group.label }}</h2>
        <RouterLink v-for="row in group.rows" :key="row.id" :to="row.to" @click="$emit('close')">
          <span><span>{{ row.label }}</span><small>{{ row.scope }}</small></span>
          <b>{{ row.state === 'error' ? '暂不可用' : row.count ?? '加载中' }}</b>
        </RouterLink>
      </section>
    </div>
    <footer>按业务范围分别统计，点击事项进入对应工作台处理。</footer>
  </section>
</template>

<style scoped>
.pending-popover { position: fixed; z-index: 1000; top: 56px; right: 16px; width: min(420px, calc(100vw - 24px)); padding: 0; overflow: hidden; color: #253b50; background: #fff; border: 1px solid #dbe2ea; border-radius: 10px; box-shadow: 0 10px 32px #17324b26; }
.pending-popover > header { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid #e7edf2; }
.pending-popover header > div { display: flex; gap: 6px; }
.pending-popover-body { max-height: min(560px, calc(100dvh - 170px)); overflow-y: auto; padding: 4px 16px 12px; }
.pending-group h2 { margin: 12px 0 4px; font-size: 12px; color: #65778b; }
.pending-group a { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 9px 0; border-bottom: 1px solid #edf1f5; color: #253b50; text-decoration: none; font-size: 12px; }
.pending-group a:hover { color: #1769aa; }
.pending-group small { display: block; margin-top: 3px; color: #708094; font-size: 10px; }
.pending-group b { flex-shrink: 0; color: #1769aa; }
.pending-popover footer, .pending-empty { padding: 12px 16px; font-size: 11px; color: #708094; }
.pending-popover footer { border-top: 1px solid #e7edf2; }
</style>
