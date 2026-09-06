import {
  clinicalContext,
  issueContextLease,
  request,
  wardHeaders,
} from '../clinical-api';
import {
  capabilityPackDeactivateRequestWireSchema,
  capabilityPackDefineRequestWireSchema,
  capabilityPackReleaseCreateRequestWireSchema,
  capabilityPackReleaseRollbackRequestWireSchema,
  capabilityPackReleaseTransitionRequestWireSchema,
  capabilityPackReleaseWireSchema,
  capabilityPackWireSchema,
  dictionaryItemCreateRequestWireSchema,
  dictionaryItemDeactivateRequestWireSchema,
  dictionaryItemWireSchema,
  historicalMigrationBatchArchiveRequestWireSchema,
  historicalMigrationBatchObservationStartRequestWireSchema,
  historicalMigrationBatchReconcileRequestWireSchema,
  historicalMigrationBatchRollbackRequestWireSchema,
  historicalMigrationBatchStartRequestWireSchema,
  historicalMigrationBatchSwitchRequestWireSchema,
  historicalMigrationBatchWireSchema,
  historicalMigrationSourceImportWireSchema,
  historicalMigrationCheckpointRecordRequestWireSchema,
  historicalMigrationCheckpointWireSchema,
  sourceFieldMappingDeactivateRequestWireSchema,
  sourceFieldMappingRegisterRequestWireSchema,
  sourceFieldMappingWireSchema,
  sourcePatientMatchCandidateRecordRequestWireSchema,
  sourcePatientMatchCandidateCsvImportRequestWireSchema,
  sourcePatientMatchCandidateResolveRequestWireSchema,
  sourcePatientMatchCandidateWireSchema,
  sourceSystemInventoryRegisterRequestWireSchema,
  sourceSystemInventoryTransitionRequestWireSchema,
  sourceSystemInventoryWireSchema,
  type CapabilityPackReleaseWire,
  type CapabilityPackWire,
  type ContextLeaseWire,
  type DictionaryItemCreateRequestWire,
  type DictionaryItemWire,
  type HistoricalMigrationBatchWire,
  type HistoricalMigrationSourceImportWire,
  type HistoricalMigrationCheckpointWire,
  type SourceFieldMappingRegisterRequestWire,
  type SourceFieldMappingWire,
  type SourcePatientMatchCandidateWire,
  type SourceSystemInventoryRegisterRequestWire,
  type SourceSystemInventoryWire,
} from '../generated/contracts';

/** 治理/配置域为机构-院区级上下文（无患者），签发一次租约后复用。 */
export function issueGovernanceLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(null, null, purpose);
}

function orgFacility() {
  return {
    organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId,
  };
}

// ── 字典主数据 ──────────────────────────────────────────────
export async function listDictionaryItems(lease: ContextLeaseWire, code: string): Promise<DictionaryItemWire[]> {
  return dictionaryItemWireSchema.array().parse(await request(
    `/dictionary-items?dictionary_code=${encodeURIComponent(code)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function createDictionaryItem(
  lease: ContextLeaseWire,
  input: Omit<DictionaryItemCreateRequestWire, 'organization_id' | 'facility_id'>,
): Promise<DictionaryItemWire> {
  return dictionaryItemWireSchema.parse(await request('/dictionary-items', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ ...orgFacility(), ...input }),
  }));
}

export async function deactivateDictionaryItem(
  lease: ContextLeaseWire,
  item: DictionaryItemWire,
): Promise<DictionaryItemWire> {
  return dictionaryItemWireSchema.parse(await request(
    `/dictionary-items/${item.dictionary_item_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...orgFacility(), expected_row_version: item.row_version }),
    },
  ));
}

// ── 能力包与灰度发布 ─────────────────────────────────────────
export async function listCapabilityPacks(lease: ContextLeaseWire, status?: string): Promise<CapabilityPackWire[]> {
  const q = status ? `?status=${encodeURIComponent(status)}` : '';
  return capabilityPackWireSchema.array().parse(await request(`/capability-packs${q}`, { headers: wardHeaders(lease) }));
}

export async function defineCapabilityPack(
  lease: ContextLeaseWire,
  input: { pack_code: string; pack_name: string; inherits_from?: string | null },
): Promise<CapabilityPackWire> {
  return capabilityPackWireSchema.parse(await request('/capability-packs', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(capabilityPackDefineRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function updateCapabilityPack(
  lease: ContextLeaseWire,
  pack: CapabilityPackWire,
  input: { pack_name: string; inherits_from?: string | null },
): Promise<CapabilityPackWire> {
  return capabilityPackWireSchema.parse(await request(`/capability-packs/${pack.capability_pack_id}`, {
    method: 'PUT',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(capabilityPackDefineRequestWireSchema.parse({
      ...orgFacility(),
      pack_code: pack.pack_code,
      pack_name: input.pack_name,
      inherits_from: input.inherits_from || null,
    })),
  }));
}

export async function deactivateCapabilityPack(lease: ContextLeaseWire, pack: CapabilityPackWire): Promise<CapabilityPackWire> {
  return capabilityPackWireSchema.parse(await request(
    `/capability-packs/${pack.capability_pack_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({ ...orgFacility(), ...capabilityPackDeactivateRequestWireSchema.parse({ ...orgFacility() }) }),
    },
  ));
}

export async function listCapabilityPackReleases(lease: ContextLeaseWire, capabilityPackId?: string): Promise<CapabilityPackReleaseWire[]> {
  const q = capabilityPackId ? `?capability_pack_id=${encodeURIComponent(capabilityPackId)}` : '';
  return capabilityPackReleaseWireSchema.array().parse(await request(`/capability-pack-releases${q}`, { headers: wardHeaders(lease) }));
}

export async function createCapabilityPackRelease(
  lease: ContextLeaseWire,
  input: { capability_pack_id: string; release_version: string; released_at: string },
): Promise<CapabilityPackReleaseWire> {
  return capabilityPackReleaseWireSchema.parse(await request('/capability-pack-releases', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(capabilityPackReleaseCreateRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

async function transitionCapabilityPackRelease(
  lease: ContextLeaseWire,
  release: CapabilityPackReleaseWire,
  action: 'start-canary' | 'promote' | 'retire',
): Promise<CapabilityPackReleaseWire> {
  return capabilityPackReleaseWireSchema.parse(await request(
    `/capability-pack-releases/${release.release_id}/${action}`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(capabilityPackReleaseTransitionRequestWireSchema.parse({ ...orgFacility(), expected_row_version: release.row_version })),
    },
  ));
}

export function startCapabilityPackReleaseCanary(lease: ContextLeaseWire, release: CapabilityPackReleaseWire) {
  return transitionCapabilityPackRelease(lease, release, 'start-canary');
}

export function promoteCapabilityPackRelease(lease: ContextLeaseWire, release: CapabilityPackReleaseWire) {
  return transitionCapabilityPackRelease(lease, release, 'promote');
}

export function retireCapabilityPackRelease(lease: ContextLeaseWire, release: CapabilityPackReleaseWire) {
  return transitionCapabilityPackRelease(lease, release, 'retire');
}

export async function rollbackCapabilityPackRelease(
  lease: ContextLeaseWire,
  release: CapabilityPackReleaseWire,
  rollback_reason: string,
): Promise<CapabilityPackReleaseWire> {
  return capabilityPackReleaseWireSchema.parse(await request(
    `/capability-pack-releases/${release.release_id}/rollback`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(capabilityPackReleaseRollbackRequestWireSchema.parse({ ...orgFacility(), expected_row_version: release.row_version, rollback_reason })),
    },
  ));
}

// ── 历史迁移（源盘点/字段映射/患者匹配/批次/断点） ────────────
export async function listSourceSystems(lease: ContextLeaseWire): Promise<SourceSystemInventoryWire[]> {
  return sourceSystemInventoryWireSchema.array().parse(await request('/source-systems', { headers: wardHeaders(lease) }));
}

export async function registerSourceSystem(
  lease: ContextLeaseWire,
  input: Omit<SourceSystemInventoryRegisterRequestWire, 'organization_id' | 'facility_id'>,
): Promise<SourceSystemInventoryWire> {
  return sourceSystemInventoryWireSchema.parse(await request('/source-systems', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(sourceSystemInventoryRegisterRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

async function transitionSourceSystem(
  lease: ContextLeaseWire,
  source: SourceSystemInventoryWire,
  action: 'configurations' | 'activations' | 'retirements',
): Promise<SourceSystemInventoryWire> {
  return sourceSystemInventoryWireSchema.parse(await request(
    `/source-systems/${source.source_system_id}/${action}`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(sourceSystemInventoryTransitionRequestWireSchema.parse({ ...orgFacility(), expected_row_version: source.row_version })),
    },
  ));
}

export function configureSourceSystem(lease: ContextLeaseWire, source: SourceSystemInventoryWire) {
  return transitionSourceSystem(lease, source, 'configurations');
}
export function activateSourceSystem(lease: ContextLeaseWire, source: SourceSystemInventoryWire) {
  return transitionSourceSystem(lease, source, 'activations');
}
export function retireSourceSystem(lease: ContextLeaseWire, source: SourceSystemInventoryWire) {
  return transitionSourceSystem(lease, source, 'retirements');
}

export async function listSourceFieldMappings(lease: ContextLeaseWire, sourceSystemId?: string): Promise<SourceFieldMappingWire[]> {
  const q = sourceSystemId ? `?source_system_id=${encodeURIComponent(sourceSystemId)}` : '';
  return sourceFieldMappingWireSchema.array().parse(await request(`/source-field-mappings${q}`, { headers: wardHeaders(lease) }));
}

export async function registerSourceFieldMapping(
  lease: ContextLeaseWire,
  input: Omit<SourceFieldMappingRegisterRequestWire, 'organization_id' | 'facility_id'>,
): Promise<SourceFieldMappingWire> {
  return sourceFieldMappingWireSchema.parse(await request('/source-field-mappings', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(sourceFieldMappingRegisterRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function deactivateSourceFieldMapping(
  lease: ContextLeaseWire,
  mapping: SourceFieldMappingWire,
): Promise<SourceFieldMappingWire> {
  return sourceFieldMappingWireSchema.parse(await request(
    `/source-field-mappings/${mapping.mapping_id}/deactivations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(sourceFieldMappingDeactivateRequestWireSchema.parse({ ...orgFacility(), expected_row_version: mapping.row_version })),
    },
  ));
}

export async function listSourcePatientMatchCandidates(lease: ContextLeaseWire, sourceSystemId: string): Promise<SourcePatientMatchCandidateWire[]> {
  return sourcePatientMatchCandidateWireSchema.array().parse(await request(
    `/source-patient-match-candidates?source_system_id=${encodeURIComponent(sourceSystemId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function listSourcePatientMatchCandidateImports(
  lease: ContextLeaseWire,
  sourceSystemId: string,
): Promise<HistoricalMigrationSourceImportWire[]> {
  return historicalMigrationSourceImportWireSchema.array().parse(await request(
    `/source-patient-match-candidate-imports?source_system_id=${encodeURIComponent(sourceSystemId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function importSourcePatientMatchCandidatesCsv(
  lease: ContextLeaseWire,
  input: { source_system_id: string; file_name: string; csv_content: string },
): Promise<HistoricalMigrationSourceImportWire> {
  return historicalMigrationSourceImportWireSchema.parse(await request('/source-patient-match-candidate-imports', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(sourcePatientMatchCandidateCsvImportRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function recordSourcePatientMatchCandidate(
  lease: ContextLeaseWire,
  input: { source_system_id: string; source_patient_identifier: string; display_name: string; sex_code: string; birth_date: string },
): Promise<SourcePatientMatchCandidateWire> {
  return sourcePatientMatchCandidateWireSchema.parse(await request('/source-patient-match-candidates', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(sourcePatientMatchCandidateRecordRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function resolveSourcePatientMatchCandidate(
  lease: ContextLeaseWire,
  candidate: SourcePatientMatchCandidateWire,
  matchedPatientId: string | null,
): Promise<SourcePatientMatchCandidateWire> {
  return sourcePatientMatchCandidateWireSchema.parse(await request(
    `/source-patient-match-candidates/${candidate.candidate_id}/resolutions`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(sourcePatientMatchCandidateResolveRequestWireSchema.parse({
        ...orgFacility(), expected_row_version: candidate.row_version, matched_patient_id: matchedPatientId,
      })),
    },
  ));
}

export async function listHistoricalMigrationBatches(lease: ContextLeaseWire, sourceSystem?: string): Promise<HistoricalMigrationBatchWire[]> {
  const q = sourceSystem ? `?source_system=${encodeURIComponent(sourceSystem)}` : '';
  return historicalMigrationBatchWireSchema.array().parse(await request(`/historical-migration-batches${q}`, { headers: wardHeaders(lease) }));
}

export async function startHistoricalMigrationBatch(
  lease: ContextLeaseWire,
  input: { source_system: string; source_import_id?: string | null; record_count: number; started_at: string },
): Promise<HistoricalMigrationBatchWire> {
  return historicalMigrationBatchWireSchema.parse(await request('/historical-migration-batches', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(historicalMigrationBatchStartRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}

export async function reconcileHistoricalMigrationBatch(
  lease: ContextLeaseWire,
  batch: HistoricalMigrationBatchWire,
  mismatchCount: number,
): Promise<HistoricalMigrationBatchWire> {
  return historicalMigrationBatchWireSchema.parse(await request(
    `/historical-migration-batches/${batch.batch_id}/reconciliations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(historicalMigrationBatchReconcileRequestWireSchema.parse({
        ...orgFacility(), mismatch_count: mismatchCount, expected_row_version: batch.row_version,
      })),
    },
  ));
}

export async function switchHistoricalMigrationBatch(
  lease: ContextLeaseWire,
  batch: HistoricalMigrationBatchWire,
): Promise<HistoricalMigrationBatchWire> {
  return historicalMigrationBatchWireSchema.parse(await request(
    `/historical-migration-batches/${batch.batch_id}/switches`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(historicalMigrationBatchSwitchRequestWireSchema.parse({ ...orgFacility(), expected_row_version: batch.row_version })),
    },
  ));
}

export async function rollbackHistoricalMigrationBatch(
  lease: ContextLeaseWire,
  batch: HistoricalMigrationBatchWire,
): Promise<HistoricalMigrationBatchWire> {
  return historicalMigrationBatchWireSchema.parse(await request(
    `/historical-migration-batches/${batch.batch_id}/rollbacks`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(historicalMigrationBatchRollbackRequestWireSchema.parse({ ...orgFacility(), expected_row_version: batch.row_version })),
    },
  ));
}

export async function startHistoricalMigrationObservation(
  lease: ContextLeaseWire,
  batch: HistoricalMigrationBatchWire,
): Promise<HistoricalMigrationBatchWire> {
  return historicalMigrationBatchWireSchema.parse(await request(
    `/historical-migration-batches/${batch.batch_id}/observations`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(historicalMigrationBatchObservationStartRequestWireSchema.parse({
        ...orgFacility(), expected_row_version: batch.row_version,
      })),
    },
  ));
}

export async function archiveHistoricalMigrationBatch(
  lease: ContextLeaseWire,
  batch: HistoricalMigrationBatchWire,
  observationSummary: string,
): Promise<HistoricalMigrationBatchWire> {
  return historicalMigrationBatchWireSchema.parse(await request(
    `/historical-migration-batches/${batch.batch_id}/archives`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(historicalMigrationBatchArchiveRequestWireSchema.parse({
        ...orgFacility(), observation_summary: observationSummary, expected_row_version: batch.row_version,
      })),
    },
  ));
}

export async function listHistoricalMigrationCheckpoints(lease: ContextLeaseWire, batchId?: string): Promise<HistoricalMigrationCheckpointWire[]> {
  const q = batchId ? `?batch_id=${encodeURIComponent(batchId)}` : '';
  return historicalMigrationCheckpointWireSchema.array().parse(await request(`/historical-migration-checkpoints${q}`, { headers: wardHeaders(lease) }));
}

export async function recordHistoricalMigrationCheckpoint(
  lease: ContextLeaseWire,
  input: { batch_id: string; processed_records: number; last_source_key?: string | null; checkpointed_at: string },
): Promise<HistoricalMigrationCheckpointWire> {
  return historicalMigrationCheckpointWireSchema.parse(await request('/historical-migration-checkpoints', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(historicalMigrationCheckpointRecordRequestWireSchema.parse({ ...orgFacility(), ...input })),
  }));
}
