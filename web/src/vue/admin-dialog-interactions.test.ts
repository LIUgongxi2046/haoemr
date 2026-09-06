import { describe, expect, it } from 'vitest';

const components = import.meta.glob('./components/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;
const views = import.meta.glob('./views/*.vue', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

describe('系统管理弹窗交互契约', () => {
  it('使用原生模态能力并提供标题、说明、关闭与焦点管理', () => {
    const dialog = components['./components/AdminActionDialog.vue'];

    expect(dialog).toContain('<dialog');
    expect(dialog).toContain('element.showModal()');
    expect(dialog).toContain(':aria-labelledby="titleId"');
    expect(dialog).toContain(':aria-describedby="description ? descriptionId : undefined"');
    expect(dialog).toContain('@cancel="handleCancel"');
    expect(dialog).toContain("'[autofocus], input:not([disabled])");
    expect(dialog).toContain('aria-label="关闭弹窗"');
  });

  it('为停用和归档提供业务影响说明及二次确认', () => {
    const confirm = components['./components/AdminConfirmDialog.vue'];

    expect(confirm).toContain('tone="danger"');
    expect(confirm).toContain('本操作会立即影响新的业务流程');
    expect(confirm).toContain("emit('confirm')");
    expect(confirm).toContain('历史病历、审计证据、签名和既有引用仍然保留');
  });

  it('覆盖系统管理全部新建入口', () => {
    const directPages = [
      'OrganizationAdministrationPage.vue',
      'WorkforceAdministrationPage.vue',
      'AdminRolesPage.vue',
      'AuthorizationAdministrationPage.vue',
      'AdminAuthPage.vue',
      'DictionaryAdministrationPage.vue',
      'DocumentTemplateAdministrationPage.vue',
      'AdminAuditPage.vue',
    ];

    for (const page of directPages) {
      expect(views[`./views/${page}`], `${page} 应使用新建弹窗`).toContain('<AdminActionDialog');
    }

    const studio = components['./components/ConfigurationStudioPage.vue'];
    expect(studio).toContain('<AdminEditorSurface');
    expect(studio).toContain("['admin-master-data', 'admin-parameters', 'admin-jobs']");
    expect(components['./components/AdminEditorSurface.vue']).toContain('<AdminActionDialog v-if="modal"');
  });

  it('不再用页面内嵌 CRUD 面板承载组织、用户、字典和权限新建', () => {
    for (const page of [
      'OrganizationAdministrationPage.vue',
      'WorkforceAdministrationPage.vue',
      'DictionaryAdministrationPage.vue',
      'AuthorizationAdministrationPage.vue',
    ]) {
      const source = views[`./views/${page}`];
      expect(source).not.toContain("panel === 'CREATE'");
      expect(source).not.toContain("panel === 'IMPORT'");
    }
  });

  it('组织、用户、字典、模板和配置归档均经过确认弹窗', () => {
    for (const page of [
      'OrganizationAdministrationPage.vue',
      'WorkforceAdministrationPage.vue',
      'DictionaryAdministrationPage.vue',
      'DocumentTemplateAdministrationPage.vue',
    ]) {
      expect(views[`./views/${page}`], `${page} 应使用高风险确认弹窗`).toContain('<AdminConfirmDialog');
    }

    const studio = components['./components/ConfigurationStudioPage.vue'];
    expect(studio).toContain('<AdminConfirmDialog');
    expect(studio).toContain("lifecycle('ARCHIVE', true)");
  });

  it('对齐系统管理原型的主标题、差异处理和模板设计入口', () => {
    expect(views['./views/WorkforceAdministrationPage.vue']).toContain('<h1>用户、人员与账户管理</h1>');
    expect(views['./views/DictionaryAdministrationPage.vue']).toContain('>处理版本差异</button>');
    expect(views['./views/DocumentTemplateAdministrationPage.vue']).toContain('>打开模板设计与修正</button>');
    expect(components['./components/ConfigurationStudioPage.vue']).toContain('/^(?:syn-)?auth-/');
  });

  it('AI 中心的模型、医助、能力、工具和额度均使用编辑与删除弹窗', () => {
    for (const page of ['ModelDeploymentPage.vue', 'AgentCatalogPage.vue', 'SkillCatalogPage.vue', 'ToolCatalogPage.vue', 'AiOpsPage.vue']) {
      const source = views[`./views/${page}`];
      expect(source, `${page} 应使用新建/编辑弹窗`).toContain('<AdminActionDialog');
      expect(source, `${page} 应使用删除确认弹窗`).toContain('<AdminConfirmDialog');
    }
  });

  it('AI 策略和医助评测在列表外层打开弹窗编辑器', () => {
    const studio = components['./components/ConfigurationStudioPage.vue'];
    expect(studio).toContain("['agent-evals']");
    expect(studio).toContain(':modal="isModalEditor"');
    expect(studio).toContain('编辑 / 版本管理');
    expect(studio).toContain('@click="requestArchive(item)"');
  });

  it('AI医助 EVA 的新任务直接创建空白聊天，清空已有任务仍确认', () => {
    const assistant = views['./views/AiAssistantPage.vue'];
    expect(assistant).toContain('@click="newTask">新建医助任务</button>');
    expect(assistant).toContain("function newTask() { messages.value = []; draft.value = '';");
    expect(assistant).not.toContain('title="新建医助任务"');
    expect(assistant).toContain('title="清空当前任务"');
    expect(assistant).toContain('<AdminConfirmDialog');
  });
});
