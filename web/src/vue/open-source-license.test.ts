import { describe, expect, it } from 'vitest';

const components = import.meta.glob('./components/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const views = import.meta.glob('./views/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

describe('HaoEMR 开源许可入口', () => {
  it('publishes the canonical source, AGPL license, commercial option, and trademark policy', () => {
    const page = views['./views/OpenSourcePage.vue'];

    expect(page).toContain('https://github.com/LIUgongxi2046/haoemr');
    expect(page).toContain('AGPL-3.0-only');
    expect(page).toContain('blob/main/LICENSE');
    expect(page).toContain('COMMERCIAL_LICENSE.md');
    expect(page).toContain('TRADEMARKS.md');
    expect(page).toContain('查看官方源码');
  });

  it('keeps the source offer visible before and after login', () => {
    const login = views['./views/LoginContextPage.vue'];
    const shell = components['./components/ClinicalShell.vue'];

    expect(login).toContain('AGPL-3.0 许可与源码');
    expect(login).toContain('https://github.com/LIUgongxi2046/haoemr');
    expect(shell).toContain('开源许可与源码');
    expect(shell).toContain('https://github.com/LIUgongxi2046/haoemr');
  });
});
