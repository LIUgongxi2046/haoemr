<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { authSession, loginClinicalSession } from '../../auth-session';
import { clinicalContext } from '../../clinical-api';
import { brandConfig } from '../../brand-config';
import { aiMedicalAssistantRoster, aiMedicalAssistantRosterSummary } from '../ai-medical-assistant-roster';

const route = useRoute();
const router = useRouter();
const form = reactive({ username: '', password: '' });
const busy = ref(false);
const message = ref('');
const passwordVisible = ref(false);

function applyContext() {
  const user = authSession.user;
  if (!user) return;
  clinicalContext.tenantId = user.tenant_id;
  clinicalContext.organizationId = user.organization_id;
  clinicalContext.facilityId = user.facility_id;
  clinicalContext.userId = user.user_id;
  clinicalContext.roleId = user.role_assignment_ids[0] ?? '';
}

async function login() {
  if (busy.value) return;
  busy.value = true; message.value = '';
  try {
    await loginClinicalSession(form.username.trim(), form.password);
    applyContext();
    const requested = typeof route.query.redirect === 'string' ? route.query.redirect : '';
    const destination = requested.startsWith('/') && !requested.startsWith('//') && !requested.startsWith('/login')
      ? requested : '/clinical';
    await router.replace(destination);
  } catch (error) {
    message.value = error instanceof Error ? error.message : '登录失败';
  } finally { busy.value = false; }
}

</script>

<template>
  <main data-page-root class="system-login-page vue-native-page">
    <section class="system-login-intro" aria-labelledby="system-name">
      <img
        class="system-login-brand"
        data-testid="system-login-logo"
        :src="brandConfig.logoUrl"
        :alt="`${brandConfig.systemName} Logo`"
        width="54"
        height="54"
      />
      <p class="system-login-kicker">CLINICAL WORKSPACE</p>
      <h1 id="system-name">{{ brandConfig.systemName || 'HaoEMR' }}</h1>
      <p>从一次接诊到出院归档，让临床信息连续、责任清楚、下一步可执行。</p>
      <ul>
        <li><span>临床工作域</span><p>门诊、急诊、住院贯通，围绕当前患者和就诊处理每一步。</p></li>
        <li><span>病历与质量</span><p>病历写得完整、签得清楚、查得到来源；质控问题可回到责任人。</p></li>
        <li><span>业务协同</span><p>把危急值、会诊、审签、执行与随访放进同一责任清单。</p></li>
        <li><span>平台中心</span><p>让数据与知识服务诊疗，AI医助 EVA 协助医生快速抓住重点。</p></li>
        <li><span>管理与配置</span><p>用统一规则、岗位授权和审计记录守住每项操作的边界。</p></li>
      </ul>
      <section class="system-login-ai-capabilities" aria-label="AI医助 EVA 能力介绍">
        <div><img src="/brand/ai-medical-assistant-eva.png" alt="" width="38" height="38" /><strong>AI医助 EVA · {{ aiMedicalAssistantRosterSummary.teamCount }} 个团队</strong></div>
        <p>当前由 {{ aiMedicalAssistantRosterSummary.specialistCount }} 个专科医助协作，覆盖从就诊摘要、文书起草到质控、结果闭环与专科诊疗支持；最终判断始终由医生完成。</p>
        <div class="system-login-ai-tags" aria-label="AI医助 EVA 团队名录"><span v-for="team in aiMedicalAssistantRoster" :key="team">{{ team }}</span></div>
      </section>
      <small>仅限授权人员使用。所有访问和操作均可审计。</small>
    </section>

    <section class="system-login-panel" aria-labelledby="login-title">
      <div class="system-login-heading">
        <span>系统统一身份认证</span>
        <h2 id="login-title">登录{{ brandConfig.systemName || 'HaoEMR' }}</h2>
        <p>请使用您的系统账号继续</p>
      </div>
      <p v-if="message" class="system-login-message" role="alert">{{ message }}</p>
      <form class="system-login-form" @submit.prevent="login">
        <div class="system-login-control"><label for="system-login-username">用户名</label><input id="system-login-username" v-model="form.username" name="username" autocomplete="username" autofocus required placeholder="请输入系统账号" /></div>
        <div class="system-login-control"><label for="system-login-password">密码</label><div class="system-password-field"><input id="system-login-password" v-model="form.password" name="password" :type="passwordVisible ? 'text' : 'password'" autocomplete="current-password" required minlength="8" placeholder="请输入密码" /><button type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" @click="passwordVisible = !passwordVisible">{{ passwordVisible ? '隐藏' : '显示' }}</button></div></div>
        <button class="system-login-submit" :disabled="busy">{{ busy ? '正在安全验证…' : '登录系统' }}</button>
      </form>
      <footer><span>数据传输加密</span><span>会话服务端撤销</span><span>审计记录留痕</span><a href="https://github.com/LIUgongxi2046/haoemr" target="_blank" rel="noreferrer">AGPL-3.0 许可与源码</a></footer>
    </section>
  </main>
</template>
