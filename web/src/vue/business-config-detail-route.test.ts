import { describe, expect, it, vi } from 'vitest';
import { createSSRApp, type Ref } from 'vue';
import { renderToString } from '@vue/server-renderer';
import { createMemoryHistory, createRouter } from 'vue-router';
import BusinessConfigurationDetailPage from './views/BusinessConfigurationDetailPage.vue';

vi.mock('@tanstack/vue-query', async () => {
  const { ref, unref } = await import('vue');
  return {
    useQuery: (options: { queryKey: unknown[] | Ref<unknown[]> }) => {
      const key = unref(options.queryKey)[1];
      return { data: ref(key === 'item' ? {
        config_id: 'config-one', display_name: '验收流程配置', config_key: 'test-workflow',
        config_type: 'WORKFLOW', status: 'DRAFT', schema_version: 3,
        validation_state: 'VALID', approval_state: 'PENDING', row_version: 1,
        payload: { nodes: [{ id: 'start' }] },
      } : key === 'lease' ? {} : []), error: ref(null) };
    },
  };
});

async function renderPath(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{
    path: '/business-config/:module/:configId?/:section?/:executionId?/:evidenceId?/:auditId?',
    component: BusinessConfigurationDetailPage,
  }, { path: '/workflow', component: { render: () => null } }] });
  await router.push(path);
  await router.isReady();
  const app = createSSRApp(BusinessConfigurationDetailPage);
  app.use(router);
  return renderToString(app);
}

describe('配置详情入口与可选路由参数', () => {
  it.each(['', '/'])('未指定分区的详情链接 %s 应显示配置概览', async (suffix) => {
    const html = await renderPath(`/business-config/workflow/config-one${suffix}`);
    expect(html).toMatch(/<h2[^>]*>验收流程配置<\/h2>/);
    expect(html).toContain('VALID / PENDING');
    expect(html).not.toContain('请求的深层对象不存在');
  });

  it('显式进入配置数据分区时，JSON 默认折叠', async () => {
    const html = await renderPath('/business-config/workflow/config-one/payload');
    expect(html).toContain('版本绑定配置载荷');
    expect(html).not.toMatch(/<h2[^>]*>验收流程配置<\/h2>/);
    expect(html).toMatch(/<summary[^>]*>展开配置 JSON<\/summary>/);
    expect(html).not.toMatch(/<details[^>]*\bopen(?:\s|=|>)/);
  });
});
