<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import ConfigurationPackPanel from '../components/ConfigurationPackPanel.vue';
import BrandSettingsPanel from '../components/BrandSettingsPanel.vue';
import ConfigurationStudioPage from '../components/ConfigurationStudioPage.vue';
import { configurationStudio } from '../configuration-studios';

const route = useRoute();
const definition = configurationStudio('admin-parameters');
const isBrandingView = computed(() => route.query.view === 'branding');
</script>

<template>
  <BrandSettingsPanel v-if="isBrandingView" />
  <template v-else>
    <div class="auth-backbar content" aria-label="参数页视图"><RouterLink class="btn" to="/admin-parameters">参数管理</RouterLink> <RouterLink class="btn" to="/admin-parameters?view=initialization">模块初始化</RouterLink></div>
    <ConfigurationPackPanel v-if="route.query.view === 'initialization'" mode="initialize" />
    <ConfigurationStudioPage v-else :definition="definition" />
  </template>
</template>
