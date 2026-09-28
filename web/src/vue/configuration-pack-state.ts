import type { ConfigurationPackOperationWire, ConfigurationPackPrecheckWire } from '../generated/contracts';

export const configurationPackStatus: Record<ConfigurationPackOperationWire['status'], string> = {
  RUNNING: '执行中', SUCCEEDED: '安装完成，待审批发布', FAILED_ROLLED_BACK: '失败，已回滚', RECOVERY_REQUIRED: '结果待确认',
};
export function canConfirmConfigurationPack(check: ConfigurationPackPrecheckWire | null, dirty: boolean, now = Date.now()) {
  return Boolean(check && check.state === 'READY' && check.trusted && !dirty && Date.parse(check.expires_at) > now);
}
export function configurationPackFailureAction(status: ConfigurationPackOperationWire['status']) {
  return status === 'FAILED_ROLLED_BACK' ? 'RECHECK' : ['RUNNING', 'RECOVERY_REQUIRED'].includes(status) ? 'QUERY' : 'TEMPLATES';
}
