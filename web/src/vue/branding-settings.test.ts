import { describe, expect, it } from 'vitest';

const components = import.meta.glob('./components/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const views = import.meta.glob('./views/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

describe('系统品牌设置入口', () => {
  it('places brand settings in the system administration submenu', () => {
    const shell = components['./components/ClinicalShell.vue'];
    expect(shell).toContain('view=branding');
    expect(shell).toContain('品牌与名称');
    expect(shell).toContain('isAdminNavigationActive');
  });

  it('supports editing and previewing the system name and Logo', () => {
    const page = components['./components/BrandSettingsPanel.vue'];
    const parameters = views['./views/AdminParametersPage.vue'];
    expect(parameters).toContain('BrandSettingsPanel');
    expect(page).toContain('保存品牌配置');
    expect(page).toContain('Logo 图片');
    expect(page).toContain('入口预览');
    expect(page).toContain('恢复项目默认');
  });
});
