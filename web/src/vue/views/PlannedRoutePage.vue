<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

import { routeById } from '../route-registry';

const route = useRoute();
const definition = computed(() => routeById.get(String(route.meta.contractId)));
</script>

<template>
  <section data-page-root class="content vue-boundary-page">
    <div class="page-heading">
      <div>
        <h1>{{ definition?.title }}</h1>
        <p>这个功能还在建设中，当前仅展示菜单和上线前的检查项。</p>
      </div>
    </div>
    <section class="migration-notice" role="status">
      <strong>设计契约已登记，生产功能尚未开放</strong>
      <p>该页面已进入 199 路由唯一注册表，但还没有通过对应业务切片的 API、权限、异常和恢复验收，因此不会展示患者数据或伪造可用按钮。</p>
    </section>
    <dl class="route-contract-card">
      <div><dt>需求</dt><dd>{{ definition?.requirement_refs.join(' · ') }}</dd></div>
      <div><dt>角色</dt><dd>{{ definition?.roles.join(' · ') }}</dd></div>
      <div><dt>守卫</dt><dd>{{ definition?.guards.join(' · ') }}</dd></div>
      <div><dt>必备状态</dt><dd>{{ definition?.states.join(' · ') }}</dd></div>
    </dl>
  </section>
</template>
