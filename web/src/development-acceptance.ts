import { reactive } from 'vue';
import { authSession } from './auth-session';
import type { DevelopmentAcceptanceSettingsWire } from './generated/contracts';
import { getDevelopmentAcceptanceSettings, updateDevelopmentAcceptanceSettings } from './api/development-acceptance';

/** Development-only UI switches cannot change server-side identity or authorization. */
export const developmentAcceptanceSettings = reactive({
  inpatientRoleSimulationEnabled: false,
  rowVersion: 0,
  loaded: false,
});

function applySettings(settings: DevelopmentAcceptanceSettingsWire): DevelopmentAcceptanceSettingsWire {
  developmentAcceptanceSettings.inpatientRoleSimulationEnabled = settings.inpatient_role_simulation_enabled;
  developmentAcceptanceSettings.rowVersion = settings.row_version;
  developmentAcceptanceSettings.loaded = true;
  return settings;
}

export function isDevelopmentAcceptanceAdministrator(): boolean {
  return Boolean(import.meta.env.DEV && authSession.user?.role_codes.includes('SYSTEM_ADMIN'));
}

export function canUseInpatientRoleSimulation(): boolean {
  return isDevelopmentAcceptanceAdministrator() && developmentAcceptanceSettings.inpatientRoleSimulationEnabled;
}

export async function loadDevelopmentAcceptanceSettings(): Promise<DevelopmentAcceptanceSettingsWire | null> {
  if (!isDevelopmentAcceptanceAdministrator()) return null;
  return applySettings(await getDevelopmentAcceptanceSettings());
}

export async function setInpatientRoleSimulationEnabled(enabled: boolean): Promise<DevelopmentAcceptanceSettingsWire> {
  if (!isDevelopmentAcceptanceAdministrator()) throw new Error('DEVELOPMENT_ACCEPTANCE_ADMIN_REQUIRED');
  return applySettings(await updateDevelopmentAcceptanceSettings({
    inpatient_role_simulation_enabled: enabled,
    expected_row_version: developmentAcceptanceSettings.rowVersion,
  }));
}
