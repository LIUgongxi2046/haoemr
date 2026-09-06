<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import BrandSettingsPanel from '../components/BrandSettingsPanel.vue';
import ConfigurationStudioPage from '../components/ConfigurationStudioPage.vue';
import { configurationStudio } from '../configuration-studios';

const route = useRoute();
const definition = configurationStudio('admin-parameters');
const isBrandingView = computed(() => route.query.view === 'branding');
</script>

<template><BrandSettingsPanel v-if="isBrandingView" /><ConfigurationStudioPage v-else :definition="definition" /></template>
