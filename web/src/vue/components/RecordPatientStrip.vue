<script setup lang="ts">
import { clinicalContext as context } from '../../clinical-api';
</script>

<template>
  <section class="patient-strip record-prototype-patient" aria-label="患者与就诊上下文">
    <div>
      <div class="patient-name">{{ context.patientDisplayName || '患者信息待核对' }}</div>
      <div class="meta">患者号 …{{ context.patientId?.slice(-6) || '待选择' }}</div>
    </div>
    <div class="divider" />
    <div>
      <b>就诊号 …{{ context.encounterId?.slice(-6) || '待选择' }}</b>
      <div class="meta">当前选中的病历就诊</div>
    </div>
    <div class="divider" />
    <div>
      <b>病历与就诊关联</b>
      <div class="meta">身份与权限由服务端逐次校验</div>
    </div>
    <div class="divider" />
    <RouterLink class="btn sm record-patient-switch" to="/outpatient">切换门诊患者</RouterLink>
  </section>
</template>

<style scoped>
@media (max-width: 820px) {
  .record-prototype-patient {
    align-items: flex-start;
    flex-wrap: wrap;
    gap: 10px 14px;
  }

  .record-prototype-patient > div:not(.divider) {
    flex: 1 1 150px;
    min-width: 0;
  }

  .record-prototype-patient .divider {
    display: none;
  }

  .record-prototype-patient b,
  .record-prototype-patient .meta {
    overflow-wrap: anywhere;
  }

  .record-prototype-patient .risk {
    flex: 0 1 auto;
    white-space: normal;
  }

  .record-prototype-patient .record-patient-switch {
    flex: 1 0 100%;
    width: 100%;
    margin-left: 0;
  }
}
</style>
