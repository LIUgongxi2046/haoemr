import {
  deviceCatalogCreateRequestWireSchema,
  deviceCatalogDeactivateRequestWireSchema,
  deviceCatalogWireSchema,
  deviceAlertTransitionRequestWireSchema,
  deviceAlertWireSchema,
  deviceObservationWireSchema,
  deviceStatusWireSchema,
  deviceStatusUnbindingRequestWireSchema,
  deviceTelemetryCollectRequestWireSchema,
  deviceTelemetryCollectResultWireSchema,
  type ContextLeaseWire,
  type DeviceCatalogCreateRequestWire,
  type DeviceCatalogWire,
  type DeviceAlertWire,
  type DeviceAlertTransitionRequestWire,
  type DeviceObservationWire,
  type DeviceStatusWire,
  type DeviceStatusBindingRequestWire,
  type DeviceStatusUnbindingRequestWire,
  type DeviceTelemetryCollectRequestWire,
  type DeviceTelemetryCollectResultWire,
} from '../generated/contracts';
import { clinicalContext, explicitContextHeaders, issueContextLease, request, wardHeaders } from '../clinical-api';

export function issueDeviceLease(): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, 'DEVICE_OPERATIONS');
}

export function issueDeviceBindingLease(patientId: string, encounterId: string): Promise<ContextLeaseWire> {
  return issueContextLease(patientId, encounterId, 'DEVICE_OPERATIONS');
}

export async function listDeviceTelemetry(
  lease: ContextLeaseWire,
  deviceCode?: string,
): Promise<DeviceObservationWire[]> {
  const suffix = deviceCode ? `?device_code=${encodeURIComponent(deviceCode)}` : '';
  return deviceObservationWireSchema.array().parse(await request(`/device-telemetry${suffix}`, {
    headers: wardHeaders(lease),
  }));
}

export async function collectDeviceTelemetry(
  lease: ContextLeaseWire,
  input: Omit<DeviceTelemetryCollectRequestWire, 'organization_id' | 'facility_id'>,
  idempotencyKey = crypto.randomUUID(),
): Promise<DeviceTelemetryCollectResultWire> {
  return deviceTelemetryCollectResultWireSchema.parse(await request('/device-telemetry', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(deviceTelemetryCollectRequestWireSchema.parse({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      ...input,
    })),
  }));
}

export async function listDeviceStatuses(
  lease: ContextLeaseWire,
  deviceCode?: string,
): Promise<DeviceStatusWire[]> {
  const suffix = deviceCode ? `?device_code=${encodeURIComponent(deviceCode)}` : '';
  return deviceStatusWireSchema.array().parse(await request(`/device-statuses${suffix}`, {
    headers: wardHeaders(lease),
  }));
}

export async function bindDeviceStatusToEncounter(
  lease: ContextLeaseWire,
  status: DeviceStatusWire,
  input: Omit<DeviceStatusBindingRequestWire, 'organization_id' | 'facility_id' | 'expected_row_version'>,
  idempotencyKey = crypto.randomUUID(),
): Promise<DeviceStatusWire> {
  return deviceStatusWireSchema.parse(await request(
    `/device-statuses/${encodeURIComponent(status.device_code)}/bindings`, {
      method: 'POST', headers: {
        ...explicitContextHeaders(lease, input.patient_id, input.encounter_id),
        'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
        expected_row_version: status.row_version, ...input }),
    },
  ));
}

export async function unbindDeviceStatusFromEncounter(
  lease: ContextLeaseWire,
  status: DeviceStatusWire,
  input: Omit<DeviceStatusUnbindingRequestWire, 'organization_id' | 'facility_id' | 'expected_row_version'>,
  idempotencyKey = crypto.randomUUID(),
): Promise<DeviceStatusWire> {
  return deviceStatusWireSchema.parse(await request(
    `/device-statuses/${encodeURIComponent(status.device_code)}/unbindings`, {
      method: 'POST', headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(deviceStatusUnbindingRequestWireSchema.parse({
        organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId,
        expected_row_version: status.row_version, ...input,
      })),
    },
  ));
}

export async function listDeviceAlerts(
  lease: ContextLeaseWire,
  deviceCode?: string,
  includeClosed = false,
): Promise<DeviceAlertWire[]> {
  const query = new URLSearchParams();
  if (deviceCode) query.set('device_code', deviceCode);
  if (includeClosed) query.set('include_closed', 'true');
  const suffix = query.size ? `?${query}` : '';
  return deviceAlertWireSchema.array().parse(await request(`/device-alerts${suffix}`, {
    headers: wardHeaders(lease),
  }));
}

export async function transitionDeviceAlert(
  lease: ContextLeaseWire,
  alert: DeviceAlertWire,
  action: DeviceAlertTransitionRequestWire['action'],
  note: string,
  idempotencyKey = crypto.randomUUID(),
): Promise<DeviceAlertWire> {
  return deviceAlertWireSchema.parse(await request(
    `/device-alerts/${encodeURIComponent(alert.device_alert_id)}/transitions`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(deviceAlertTransitionRequestWireSchema.parse({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
        expected_row_version: alert.row_version,
        action,
        note,
      })),
    },
  ));
}

export async function listDevices(
  lease: ContextLeaseWire,
  status?: string,
): Promise<DeviceCatalogWire[]> {
  const suffix = status ? `?status=${encodeURIComponent(status)}` : '';
  return deviceCatalogWireSchema.array().parse(await request(`/devices${suffix}`, {
    headers: wardHeaders(lease),
  }));
}

export async function createDevice(
  lease: ContextLeaseWire,
  input: Omit<DeviceCatalogCreateRequestWire, 'organization_id' | 'facility_id'>,
  idempotencyKey = crypto.randomUUID(),
): Promise<DeviceCatalogWire> {
  return deviceCatalogWireSchema.parse(await request('/devices', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(deviceCatalogCreateRequestWireSchema.parse({
      organization_id: clinicalContext.organizationId,
      facility_id: clinicalContext.facilityId,
      ...input,
    })),
  }));
}

export async function deactivateDevice(
  lease: ContextLeaseWire,
  deviceId: string,
  idempotencyKey = crypto.randomUUID(),
): Promise<DeviceCatalogWire> {
  return deviceCatalogWireSchema.parse(await request(
    `/devices/${encodeURIComponent(deviceId)}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(deviceCatalogDeactivateRequestWireSchema.parse({
        organization_id: clinicalContext.organizationId,
        facility_id: clinicalContext.facilityId,
      })),
    },
  ));
}
