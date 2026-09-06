import { describe, expect, it } from 'vitest';
import page from './views/ClinicalTasksPage.vue?raw';

describe('任务中心布局与临床路径规则契约', () => {
  it('消息通知的任务选择框和新建按钮保持同一行且不会溢出', () => {
    expect(page).toContain('toolbar-actions notification-create-toolbar');
    expect(page).toContain('grid-template-columns:minmax(0,360px) auto');
    expect(page).toContain('width:min(480px,100%)');
    expect(page).toContain('.notification-create-toolbar .button{white-space:nowrap}');
    expect(page).toContain('text-overflow:ellipsis');
  });

  it('路径页面明确展示规则、版本快照和可执行必做任务', () => {
    expect(page).toContain('临床路径配置与发布');
    expect(page).toContain('版本是发布快照');
    expect(page).toContain("pathwayRuleCount(item, 'entry_rules')");
    expect(page).toContain("pathwayRuleCount(item, 'variance_rules')");
    expect(page).toContain('pathwayRequiredTaskCount(item)');
    for (const rule of ['entry_rules', 'exclusion_rules', 'variance_rules', 'completion_rules', 'exit_rules']) {
      expect(page).toContain(`${rule}:`);
    }
  });

  it('支持门急住三域、真实协作资格和可展开的技术详情', () => {
    expect(page).toContain("['emergency','急诊']");
    expect(page).toContain('<option value="emergency">急诊</option>');
    expect(page).toContain('listEligibleClinicalTaskCollaborators');
    expect(page).toContain('请选择符合资质的人员');
    expect(page).toContain('委托截止时间');
    for (const level of ['任务详情', '任务来源', '处理记录', '查看技术详情']) {
      expect(page).toContain(level);
    }
    expect(page).toContain('target_type: \'TASK\'');
  });

  it('协作动作与后端状态机一致且切换菜单会清除旧横幅', () => {
    expect(page).toContain("if (task.state === 'CLAIMED') return ['delegations', 'transfers', 'escalations']");
    expect(page).toContain("if (task.state === 'IN_PROGRESS') return ['escalations']");
    expect(page).toContain('v-for="action in collaborationActions"');
    expect(page).toContain('watch(activeView, () => {');
    expect(page).toContain('notice.value = \'\';');
  });

  it('院内消息总线必须等待适配器回执，页面只允许站内消息确认送达', () => {
    expect(page).toContain("item.status === 'PENDING' && item.channel === 'IN_APP'");
    expect(page).toContain('等待适配器回执');
    expect(page).toContain('不会被伪标为已送达');
  });

  it('可以只读查看已过期任务历史，并在页面完成独立批准和发布', () => {
    expect(page).toContain('显示已过期历史');
    expect(page).toContain('includeExpired');
    expect(page).toContain("lifecycle(item, 'APPROVE')");
    expect(page).toContain("lifecycle(item, 'PUBLISH')");
    expect(page).toContain('独立批准');
    expect(page).toContain('仅影响后续新流程');
  });
});
