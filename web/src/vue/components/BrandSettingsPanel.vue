<script setup lang="ts">
import { ref } from 'vue';
import {
  brandConfig,
  DEFAULT_BRAND_CONFIG,
  readLogoFile,
  resetBrandConfig,
  saveBrandConfig,
} from '../../brand-config';

const systemName = ref(brandConfig.systemName);
const logoUrl = ref(brandConfig.logoUrl);
const busy = ref(false);
const notice = ref('');
const noticeIsError = ref(false);

function setNotice(message: string, error = false) {
  notice.value = message;
  noticeIsError.value = error;
}

async function selectLogo(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    logoUrl.value = await readLogoFile(file);
    setNotice('Logo 已载入预览，点击保存后在系统入口生效。');
  } catch (error) {
    setNotice(error instanceof Error ? error.message : 'Logo 读取失败。', true);
  } finally {
    (event.target as HTMLInputElement).value = '';
  }
}

function save() {
  if (busy.value) return;
  const name = systemName.value.trim();
  if (!name) {
    setNotice('系统名称不能为空。', true);
    return;
  }
  busy.value = true;
  saveBrandConfig({ systemName: name, logoUrl: logoUrl.value });
  setNotice('品牌配置已保存，登录页和顶栏已同步更新。');
  busy.value = false;
}

function restoreDefaults() {
  if (busy.value) return;
  busy.value = true;
  const next = resetBrandConfig();
  systemName.value = next.systemName;
  logoUrl.value = next.logoUrl;
  setNotice('已恢复项目默认 Logo 和系统名称。');
  busy.value = false;
}
</script>

<template>
  <section data-page-root class="content vue-native-page brand-settings-page">
    <div class="page-head">
      <div class="page-title"><p class="eyebrow">系统管理 / 品牌与名称</p><h1>品牌与名称设置</h1><p>统一维护登录页、系统顶栏使用的 Logo 与系统名称。</p></div>
      <div class="head-actions"><button class="btn" type="button" :disabled="busy" @click="restoreDefaults">恢复项目默认</button><button class="btn primary" type="button" :disabled="busy" @click="save">保存品牌配置</button></div>
    </div>
    <p v-if="notice" class="inline-notice" :class="{ error: noticeIsError }" role="status">{{ notice }}</p>
    <div class="brand-settings-grid">
      <section class="card">
        <div class="card-head"><div><h2>品牌信息</h2><p>修改后会同步应用到登录入口和已登录工作台。</p></div><span class="status blue">可预览</span></div>
        <form class="brand-settings-form" @submit.prevent="save">
          <label><span>系统名称</span><input v-model="systemName" maxlength="80" required placeholder="例如：江城医院电子病历系统" /></label>
          <label><span>Logo 图片</span><input type="file" accept="image/png,image/jpeg,image/webp,image/gif" @change="selectLogo" /><small>支持 PNG、JPG、WEBP、GIF，大小不超过 1.5 MB。</small></label>
          <label><span>Logo 地址（可选）</span><input v-model="logoUrl" maxlength="2000000" placeholder="/brand/your-logo.png 或 https://…" /><small>上传图片后会自动填入；也可以填写同源静态资源地址。</small></label>
          <div class="brand-form-actions"><button class="button secondary" type="button" :disabled="busy" @click="restoreDefaults">重置表单</button><button class="button primary" type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存品牌配置' }}</button></div>
        </form>
      </section>
      <section class="card brand-preview-card">
        <div class="card-head"><div><h2>入口预览</h2><p>实时查看系统标识在页面中的展示效果。</p></div><span class="status green">实时</span></div>
        <div class="brand-preview-login"><div class="preview-logo-frame"><img :src="logoUrl || DEFAULT_BRAND_CONFIG.logoUrl" :alt="`${systemName || DEFAULT_BRAND_CONFIG.systemName} Logo 预览`" /></div><strong>{{ systemName || DEFAULT_BRAND_CONFIG.systemName }}</strong><span>统一身份认证入口</span></div>
        <div class="brand-preview-topbar"><img :src="logoUrl || DEFAULT_BRAND_CONFIG.logoUrl" alt="" /><span><b>{{ systemName || DEFAULT_BRAND_CONFIG.systemName }}</b><small>电子病历系统</small></span></div>
        <p class="brand-preview-note">配置保存在当前浏览器的项目运行配置中，刷新页面后仍会保留。</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.brand-settings-grid{display:grid;grid-template-columns:minmax(0,1.08fr) minmax(300px,.92fr);gap:16px}.brand-settings-form{display:grid;gap:16px;padding:16px}.brand-settings-form label{display:grid;gap:7px;color:var(--ink);font-size:12px;font-weight:700}.brand-settings-form input[type=text],.brand-settings-form input[type=file]{min-width:0}.brand-settings-form small{color:var(--muted);font-size:10px;font-weight:400;line-height:1.5}.brand-form-actions{display:flex;justify-content:flex-end;gap:9px;padding-top:2px}.brand-preview-card{min-width:0}.brand-preview-login{display:grid;place-items:center;gap:10px;min-height:220px;padding:24px 16px;background:linear-gradient(145deg,#0d4774,#143452);color:#fff;text-align:center}.preview-logo-frame{display:grid;place-items:center;width:76px;height:76px;padding:9px;border:1px solid rgba(255,255,255,.35);border-radius:18px;background:#fff;box-shadow:0 9px 24px rgba(4,25,45,.25)}.preview-logo-frame img{width:58px;height:58px;object-fit:contain}.brand-preview-login strong{font-size:19px}.brand-preview-login>span{color:#d9ebf7;font-size:11px}.brand-preview-topbar{display:flex;align-items:center;gap:10px;padding:16px;border-bottom:1px solid var(--line)}.brand-preview-topbar img{width:38px;height:38px;border:1px solid #d4a800;border-radius:10px;object-fit:cover;background:#fff}.brand-preview-topbar span{display:grid;gap:3px}.brand-preview-topbar b{font-size:12px}.brand-preview-topbar small{color:var(--muted);font-size:10px}.brand-preview-note{margin:0;padding:13px 16px;color:var(--muted);font-size:10px;line-height:1.6}.inline-notice.error{color:var(--red);border-color:color-mix(in srgb,var(--red) 35%,white);background:#fff8f8}@media(max-width:850px){.brand-settings-grid{grid-template-columns:1fr}}@media(max-width:600px){.brand-form-actions{flex-direction:column}.brand-form-actions button{width:100%}}
</style>
