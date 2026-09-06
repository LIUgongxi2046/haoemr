import {
  developmentAcceptanceSettingsUpdateRequestWireSchema,
  developmentAcceptanceSettingsWireSchema,
  type DevelopmentAcceptanceSettingsWire,
} from '../generated/contracts';
import { request } from '../clinical-api';

export async function getDevelopmentAcceptanceSettings(): Promise<DevelopmentAcceptanceSettingsWire> {
  return developmentAcceptanceSettingsWireSchema.parse(await request('/development-acceptance-settings'));
}

export async function updateDevelopmentAcceptanceSettings(input: {
  inpatient_role_simulation_enabled: boolean;
  expected_row_version: number;
}): Promise<DevelopmentAcceptanceSettingsWire> {
  return developmentAcceptanceSettingsWireSchema.parse(await request('/development-acceptance-settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(developmentAcceptanceSettingsUpdateRequestWireSchema.parse(input)),
  }));
}
