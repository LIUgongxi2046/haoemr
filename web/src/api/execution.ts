import {
  clinicalContext,
  explicitContextHeaders,
  issueContextLease,
  request,
  scopedHeaders,
  wardHeaders,
} from '../clinical-api';
import {
  bloodTransfusionReactionRequestWireSchema,
  bloodTransfusionRecordRequestWireSchema,
  bloodTransfusionWireSchema,
  chargeItemRequestWireSchema,
  chargeItemReverseRequestWireSchema,
  chargeItemWireSchema,
  imagingOrderCreateRequestWireSchema,
  imagingOrderTransitionRequestWireSchema,
  imagingOrderWireSchema,
  labSpecimenCollectRequestWireSchema,
  labSpecimenCreateRequestWireSchema,
  labSpecimenReceiveRequestWireSchema,
  labSpecimenRejectRequestWireSchema,
  labSpecimenWireSchema,
  medicationAdministrationRequestWireSchema,
  medicationAdministrationWireSchema,
  nursingBedsideNoteCreateRequestWireSchema,
  nursingBedsideNoteWireSchema,
  nursingCarePlanCompleteRequestWireSchema,
  nursingCarePlanRequestWireSchema,
  nursingCarePlanWireSchema,
  nursingDischargeClosureRequestWireSchema,
  nursingDischargeClosureWireSchema,
  pharmacyDispensingPrepareRequestWireSchema,
  pharmacyDispensingTransitionRequestWireSchema,
  pharmacyDispensingUpdateRequestWireSchema,
  pharmacyDispensingVoidRequestWireSchema,
  pharmacyDispensingWireSchema,
  priceCatalogVersionRequestWireSchema,
  priceCatalogVersionWireSchema,
  encounterSettlementWireSchema,
  settlementReceiptCreateRequestWireSchema,
  settlementReceiptWireSchema,
  shiftHandoverCompleteRequestWireSchema,
  shiftHandoverCreateRequestWireSchema,
  shiftHandoverPatientCreateRequestWireSchema,
  shiftHandoverPatientWireSchema,
  shiftHandoverVoidRequestWireSchema,
  shiftHandoverWireSchema,
  surgicalProcedureScheduleRequestWireSchema,
  surgicalTimeOutVerificationRequestWireSchema,
  surgicalProcedureTransitionRequestWireSchema,
  surgicalProcedureWireSchema,
  vitalSignRecordRequestWireSchema,
  vitalSignRecordWireSchema,
  type BloodTransfusionWire,
  type ChargeItemWire,
  type ContextLeaseWire,
  type ImagingOrderWire,
  type LabSpecimenWire,
  type MedicationAdministrationWire,
  type NursingBedsideNoteWire,
  type NursingCarePlanWire,
  type NursingDischargeClosureWire,
  type PharmacyDispensingWire,
  type PriceCatalogVersionWire,
  type EncounterSettlementWire,
  type SettlementReceiptWire,
  type ShiftHandoverPatientWire,
  type ShiftHandoverWire,
  type SurgicalProcedureWire,
  type VitalSignRecordWire,
} from '../generated/contracts';

/**
 * 诊疗执行域（药房/收费/检验/影像/输血/手术/护理）API 客户端。
 * 除科室级交接班外，全部按「患者 + 就诊」签发上下文租约，复用 clinical-api 的请求内核。
 */
export function issueExecutionLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(clinicalContext.patientId, clinicalContext.encounterId, purpose);
}

/** 患者级（无就诊）租约：药房/影像/手术/出院闭环/床旁记录等端点 authorize 传 encounter=null。 */
export function issueExecutionPatientLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(clinicalContext.patientId, null, purpose);
}

export function issueInpatientExecutionLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(clinicalContext.inpatientPatientId, clinicalContext.inpatientEncounterId, purpose);
}

export function issueInpatientExecutionPatientLease(purpose: string): Promise<ContextLeaseWire> {
  return issueContextLease(clinicalContext.inpatientPatientId, null, purpose);
}

/** 患者 + 就诊级别的命令上下文（绝大多数执行域写操作需要）。 */
function scoped() {
  return {
    organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId,
    patient_id: clinicalContext.patientId,
    encounter_id: clinicalContext.encounterId,
  };
}

/** 科室级上下文（交接班用，无需患者）。 */
function wardScope() {
  return {
    organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId,
    ward_id: clinicalContext.inpatientWardId,
  };
}

function json(method: string, lease: ContextLeaseWire, body: unknown) {
  return {
    method,
    headers: {
      ...scopedHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify(body),
  };
}

/** 患者级（无就诊）请求头：不携带 X-Encounter-Context。 */
function patientOnlyHeaders(lease: ContextLeaseWire) {
  return explicitContextHeaders(lease, clinicalContext.patientId, null);
}

function patientJson(method: string, lease: ContextLeaseWire, body: unknown) {
  return {
    method,
    headers: {
      ...patientOnlyHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify(body),
  };
}

function inpatientScoped() {
  return {
    organization_id: clinicalContext.organizationId,
    facility_id: clinicalContext.facilityId,
    patient_id: clinicalContext.inpatientPatientId,
    encounter_id: clinicalContext.inpatientEncounterId,
  };
}

function inpatientPatientHeaders(lease: ContextLeaseWire) {
  return explicitContextHeaders(lease, clinicalContext.inpatientPatientId, null);
}

function inpatientJson(method: string, lease: ContextLeaseWire, body: unknown) {
  return {
    method,
    headers: {
      ...explicitContextHeaders(lease, clinicalContext.inpatientPatientId, clinicalContext.inpatientEncounterId),
      'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID(),
    },
    body: JSON.stringify(body),
  };
}

// ── 收费与价格（费用、收退费） ───────────────────────────────
export async function listCharges(lease: ContextLeaseWire): Promise<ChargeItemWire[]> {
  return chargeItemWireSchema.array().parse(await request(
    `/charges?encounter_id=${encodeURIComponent(clinicalContext.encounterId)}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function createCharge(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').ChargeItemRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<ChargeItemWire> {
  return chargeItemWireSchema.parse(await request('/charges', json('POST', lease, chargeItemRequestWireSchema.parse({ ...scoped(), ...input }))));
}

export async function reverseCharge(
  lease: ContextLeaseWire,
  charge: ChargeItemWire,
  reason: string,
): Promise<ChargeItemWire> {
  return chargeItemWireSchema.parse(await request(
    `/charges/${charge.charge_item_id}/reversals`,
    json('POST', lease, chargeItemReverseRequestWireSchema.parse({ ...scoped(), expected_row_version: charge.row_version, reason })),
  ));
}

export async function createPriceCatalogVersion(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').PriceCatalogVersionRequestWire, 'organization_id' | 'facility_id'>,
): Promise<PriceCatalogVersionWire> {
  return priceCatalogVersionWireSchema.parse(await request('/price-catalogs', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(priceCatalogVersionRequestWireSchema.parse({
      organization_id: clinicalContext.organizationId, facility_id: clinicalContext.facilityId, ...input,
    })),
  }));
}

export async function getEncounterSettlement(lease: ContextLeaseWire): Promise<EncounterSettlementWire> {
  return encounterSettlementWireSchema.parse(await request(
    `/settlements?encounter_id=${encodeURIComponent(clinicalContext.encounterId)}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function recordSettlementReceipt(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').SettlementReceiptCreateRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<SettlementReceiptWire> {
  return settlementReceiptWireSchema.parse(await request('/settlements/receipts',
    json('POST', lease, settlementReceiptCreateRequestWireSchema.parse({ ...scoped(), ...input }))));
}

// ── 检验工作台（标本闭环） ───────────────────────────────────
export async function listLabSpecimens(lease: ContextLeaseWire): Promise<LabSpecimenWire[]> {
  return labSpecimenWireSchema.array().parse(await request(
    `/lab-specimens?encounter_id=${encodeURIComponent(clinicalContext.encounterId)}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function createLabSpecimen(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').LabSpecimenCreateRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<LabSpecimenWire> {
  return labSpecimenWireSchema.parse(await request('/lab-specimens', json('POST', lease, labSpecimenCreateRequestWireSchema.parse({ ...scoped(), ...input }))));
}

export async function collectLabSpecimen(lease: ContextLeaseWire, specimen: LabSpecimenWire): Promise<LabSpecimenWire> {
  return labSpecimenWireSchema.parse(await request(
    `/lab-specimens/${specimen.specimen_id}/collections`,
    json('POST', lease, labSpecimenCollectRequestWireSchema.parse({ ...scoped(), expected_row_version: specimen.row_version })),
  ));
}

export async function receiveLabSpecimen(lease: ContextLeaseWire, specimen: LabSpecimenWire): Promise<LabSpecimenWire> {
  return labSpecimenWireSchema.parse(await request(
    `/lab-specimens/${specimen.specimen_id}/receptions`,
    json('POST', lease, labSpecimenReceiveRequestWireSchema.parse({ ...scoped(), expected_row_version: specimen.row_version })),
  ));
}

export async function rejectLabSpecimen(lease: ContextLeaseWire, specimen: LabSpecimenWire, reason: string): Promise<LabSpecimenWire> {
  return labSpecimenWireSchema.parse(await request(
    `/lab-specimens/${specimen.specimen_id}/rejections`,
    json('POST', lease, labSpecimenRejectRequestWireSchema.parse({
      ...scoped(), expected_row_version: specimen.row_version, rejection_reason: reason,
    })),
  ));
}

// ── 检查影像工作台（预约闭环） ───────────────────────────────
export async function listImagingOrders(lease: ContextLeaseWire): Promise<ImagingOrderWire[]> {
  return imagingOrderWireSchema.array().parse(await request(
    `/imaging-orders?patient_id=${encodeURIComponent(clinicalContext.patientId)}`,
    { headers: patientOnlyHeaders(lease) },
  ));
}

export async function createImagingOrder(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').ImagingOrderCreateRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<ImagingOrderWire> {
  return imagingOrderWireSchema.parse(await request('/imaging-orders', json('POST', lease, imagingOrderCreateRequestWireSchema.parse({ ...scoped(), ...input }))));
}

export async function transitionImagingOrder(
  lease: ContextLeaseWire,
  order: ImagingOrderWire,
  transition: 'PERFORM' | 'REPORT' | 'CANCEL',
): Promise<ImagingOrderWire> {
  return imagingOrderWireSchema.parse(await request(
    `/imaging-orders/${order.imaging_order_id}/transitions`,
    json('POST', lease, imagingOrderTransitionRequestWireSchema.parse({ ...scoped(), expected_row_version: order.row_version, transition })),
  ));
}

// ── 药房（门诊审方调剂 / 住院摆药发药，双人核验） ──────────────
export async function listPharmacyDispensings(lease: ContextLeaseWire): Promise<PharmacyDispensingWire[]> {
  return pharmacyDispensingWireSchema.array().parse(await request(
    `/pharmacy-dispensings?patient_id=${encodeURIComponent(clinicalContext.patientId)}`,
    { headers: patientOnlyHeaders(lease) },
  ));
}

export async function preparePharmacyDispensing(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').PharmacyDispensingPrepareRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<PharmacyDispensingWire> {
  return pharmacyDispensingWireSchema.parse(await request('/pharmacy-dispensings', json('POST', lease, pharmacyDispensingPrepareRequestWireSchema.parse({ ...scoped(), ...input }))));
}

export async function transitionPharmacyDispensing(
  lease: ContextLeaseWire,
  dispensing: PharmacyDispensingWire,
  transition: 'VERIFY' | 'DISPENSE',
): Promise<PharmacyDispensingWire> {
  return pharmacyDispensingWireSchema.parse(await request(
    `/pharmacy-dispensings/${dispensing.dispensing_id}/transitions`,
    json('POST', lease, pharmacyDispensingTransitionRequestWireSchema.parse({ ...scoped(), expected_row_version: dispensing.row_version, transition })),
  ));
}

export async function listInpatientPharmacyDispensings(lease: ContextLeaseWire): Promise<PharmacyDispensingWire[]> {
  return pharmacyDispensingWireSchema.array().parse(await request(
    `/pharmacy-dispensings?patient_id=${encodeURIComponent(clinicalContext.inpatientPatientId)}`,
    { headers: inpatientPatientHeaders(lease) },
  ));
}

export async function prepareInpatientPharmacyDispensing(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').PharmacyDispensingPrepareRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<PharmacyDispensingWire> {
  return pharmacyDispensingWireSchema.parse(await request('/pharmacy-dispensings', inpatientJson('POST', lease,
    pharmacyDispensingPrepareRequestWireSchema.parse({ ...inpatientScoped(), ...input }))));
}

export async function transitionInpatientPharmacyDispensing(
  lease: ContextLeaseWire,
  dispensing: PharmacyDispensingWire,
  transition: 'VERIFY' | 'DISPENSE',
): Promise<PharmacyDispensingWire> {
  return pharmacyDispensingWireSchema.parse(await request(
    `/pharmacy-dispensings/${dispensing.dispensing_id}/transitions`,
    inpatientJson('POST', lease, pharmacyDispensingTransitionRequestWireSchema.parse({
      ...inpatientScoped(), expected_row_version: dispensing.row_version, transition,
    })),
  ));
}

export async function updateInpatientPharmacyDispensing(
  lease: ContextLeaseWire,
  dispensing: PharmacyDispensingWire,
  input: Pick<import('../generated/contracts').PharmacyDispensingUpdateRequestWire,
    'drug_code' | 'batch_number' | 'quantity' | 'quantity_unit'>,
): Promise<PharmacyDispensingWire> {
  return pharmacyDispensingWireSchema.parse(await request(
    `/pharmacy-dispensings/${dispensing.dispensing_id}`,
    inpatientJson('PATCH', lease, pharmacyDispensingUpdateRequestWireSchema.parse({
      ...inpatientScoped(), expected_row_version: dispensing.row_version, ...input,
    })),
  ));
}

export async function voidInpatientPharmacyDispensing(
  lease: ContextLeaseWire,
  dispensing: PharmacyDispensingWire,
  reason: string,
): Promise<PharmacyDispensingWire> {
  return pharmacyDispensingWireSchema.parse(await request(
    `/pharmacy-dispensings/${dispensing.dispensing_id}/voids`,
    inpatientJson('POST', lease, pharmacyDispensingVoidRequestWireSchema.parse({
      ...inpatientScoped(), expected_row_version: dispensing.row_version, reason,
    })),
  ));
}

// ── 输血全链（双人核验 + 输注反应） ───────────────────────────
export async function listBloodTransfusions(lease: ContextLeaseWire): Promise<BloodTransfusionWire[]> {
  return bloodTransfusionWireSchema.array().parse(await request(
    `/blood-transfusions?encounter_id=${encodeURIComponent(clinicalContext.encounterId)}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function recordBloodTransfusion(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').BloodTransfusionRecordRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<BloodTransfusionWire> {
  return bloodTransfusionWireSchema.parse(await request('/blood-transfusions', json('POST', lease, bloodTransfusionRecordRequestWireSchema.parse({ ...scoped(), ...input }))));
}

export async function recordBloodTransfusionReaction(
  lease: ContextLeaseWire,
  transfusion: BloodTransfusionWire,
  reactionType: BloodTransfusionWire['reaction_type'] & string,
): Promise<BloodTransfusionWire> {
  return bloodTransfusionWireSchema.parse(await request(
    `/blood-transfusions/${transfusion.transfusion_id}/reactions`,
    json('POST', lease, bloodTransfusionReactionRequestWireSchema.parse({
      ...scoped(), expected_row_version: transfusion.row_version, reaction_type: reactionType,
    })),
  ));
}

// ── 围手术期（排程 + 安全核查） ───────────────────────────────
export async function listSurgicalProcedures(lease: ContextLeaseWire): Promise<SurgicalProcedureWire[]> {
  return surgicalProcedureWireSchema.array().parse(await request(
    `/surgical-procedures?patient_id=${encodeURIComponent(clinicalContext.patientId)}`,
    { headers: patientOnlyHeaders(lease) },
  ));
}

export async function scheduleSurgicalProcedure(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').SurgicalProcedureScheduleRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<SurgicalProcedureWire> {
  return surgicalProcedureWireSchema.parse(await request('/surgical-procedures', json('POST', lease, surgicalProcedureScheduleRequestWireSchema.parse({ ...scoped(), ...input }))));
}

export async function transitionSurgicalProcedure(
  lease: ContextLeaseWire,
  procedure: SurgicalProcedureWire,
  transition: 'TIME_OUT' | 'COMPLETE',
): Promise<SurgicalProcedureWire> {
  return surgicalProcedureWireSchema.parse(await request(
    `/surgical-procedures/${procedure.surgical_procedure_id}/transitions`,
    json('POST', lease, surgicalProcedureTransitionRequestWireSchema.parse({ ...scoped(), expected_row_version: procedure.row_version, transition })),
  ));
}

export async function verifySurgicalProcedureTimeOut(
  lease: ContextLeaseWire,
  procedure: SurgicalProcedureWire,
  verificationNote: string,
): Promise<SurgicalProcedureWire> {
  return surgicalProcedureWireSchema.parse(await request(
    `/surgical-procedures/${procedure.surgical_procedure_id}/time-out-verifications`,
    json('POST', lease, surgicalTimeOutVerificationRequestWireSchema.parse({
      ...scoped(), expected_procedure_row_version: procedure.row_version, verification_note: verificationNote,
    })),
  ));
}

// ── 诊疗执行中心（护理体征/计划/给药/交接/出院/床旁） ──────────
export async function listVitalSigns(lease: ContextLeaseWire): Promise<VitalSignRecordWire[]> {
  return vitalSignRecordWireSchema.array().parse(await request(
    `/vital-signs?encounter_id=${encodeURIComponent(clinicalContext.encounterId)}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function recordVitalSigns(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').VitalSignRecordRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<VitalSignRecordWire> {
  return vitalSignRecordWireSchema.parse(await request('/vital-signs', json('POST', lease, vitalSignRecordRequestWireSchema.parse({ ...scoped(), ...input }))));
}

export async function listInpatientVitalSigns(lease: ContextLeaseWire): Promise<VitalSignRecordWire[]> {
  return vitalSignRecordWireSchema.array().parse(await request(
    `/vital-signs?encounter_id=${encodeURIComponent(clinicalContext.inpatientEncounterId)}`,
    { headers: explicitContextHeaders(lease, clinicalContext.inpatientPatientId, clinicalContext.inpatientEncounterId) },
  ));
}

export async function recordInpatientVitalSigns(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').VitalSignRecordRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<VitalSignRecordWire> {
  return vitalSignRecordWireSchema.parse(await request('/vital-signs', inpatientJson('POST', lease,
    vitalSignRecordRequestWireSchema.parse({ ...inpatientScoped(), ...input }))));
}

export async function listNursingCarePlans(lease: ContextLeaseWire): Promise<NursingCarePlanWire[]> {
  return nursingCarePlanWireSchema.array().parse(await request(
    `/nursing-care-plans?encounter_id=${encodeURIComponent(clinicalContext.encounterId)}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function createNursingCarePlan(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').NursingCarePlanRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<NursingCarePlanWire> {
  return nursingCarePlanWireSchema.parse(await request('/nursing-care-plans', json('POST', lease, nursingCarePlanRequestWireSchema.parse({ ...scoped(), ...input }))));
}

export async function completeNursingCarePlan(
  lease: ContextLeaseWire,
  plan: NursingCarePlanWire,
  disposition: 'COMPLETED' | 'DISCONTINUED',
  evaluation?: string | null,
): Promise<NursingCarePlanWire> {
  return nursingCarePlanWireSchema.parse(await request(
    `/nursing-care-plans/${plan.care_plan_id}/completions`,
    json('POST', lease, nursingCarePlanCompleteRequestWireSchema.parse({
      ...scoped(), expected_row_version: plan.row_version, disposition, evaluation,
    })),
  ));
}

export async function listInpatientNursingCarePlans(lease: ContextLeaseWire): Promise<NursingCarePlanWire[]> {
  return nursingCarePlanWireSchema.array().parse(await request(
    `/nursing-care-plans?encounter_id=${encodeURIComponent(clinicalContext.inpatientEncounterId)}`,
    { headers: explicitContextHeaders(lease, clinicalContext.inpatientPatientId, clinicalContext.inpatientEncounterId) },
  ));
}

export async function createInpatientNursingCarePlan(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').NursingCarePlanRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<NursingCarePlanWire> {
  return nursingCarePlanWireSchema.parse(await request('/nursing-care-plans', inpatientJson('POST', lease,
    nursingCarePlanRequestWireSchema.parse({ ...inpatientScoped(), ...input }))));
}

export async function completeInpatientNursingCarePlan(
  lease: ContextLeaseWire,
  plan: NursingCarePlanWire,
  disposition: 'COMPLETED' | 'DISCONTINUED',
  evaluation?: string | null,
): Promise<NursingCarePlanWire> {
  return nursingCarePlanWireSchema.parse(await request(
    `/nursing-care-plans/${plan.care_plan_id}/completions`,
    inpatientJson('POST', lease, nursingCarePlanCompleteRequestWireSchema.parse({
      ...inpatientScoped(), expected_row_version: plan.row_version, disposition, evaluation,
    })),
  ));
}

export async function listMedicationAdministrations(lease: ContextLeaseWire): Promise<MedicationAdministrationWire[]> {
  return medicationAdministrationWireSchema.array().parse(await request(
    `/medication-administrations?encounter_id=${encodeURIComponent(clinicalContext.encounterId)}`,
    { headers: scopedHeaders(lease) },
  ));
}

export async function administerMedication(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').MedicationAdministrationRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<MedicationAdministrationWire> {
  return medicationAdministrationWireSchema.parse(await request('/medication-administrations', json('POST', lease, medicationAdministrationRequestWireSchema.parse({ ...scoped(), ...input }))));
}

// 交接班（科室级，无患者上下文）
export async function listShiftHandovers(lease: ContextLeaseWire): Promise<ShiftHandoverWire[]> {
  return shiftHandoverWireSchema.array().parse(await request(
    `/shift-handovers?ward_id=${encodeURIComponent(clinicalContext.inpatientWardId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function createShiftHandover(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').ShiftHandoverCreateRequestWire, 'organization_id' | 'facility_id' | 'ward_id'>,
): Promise<ShiftHandoverWire> {
  return shiftHandoverWireSchema.parse(await request('/shift-handovers', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(shiftHandoverCreateRequestWireSchema.parse({ ...wardScope(), ...input })),
  }));
}

export async function completeShiftHandover(lease: ContextLeaseWire, handover: ShiftHandoverWire): Promise<ShiftHandoverWire> {
  return shiftHandoverWireSchema.parse(await request(
    `/shift-handovers/${handover.handover_id}/completions`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(shiftHandoverCompleteRequestWireSchema.parse({ ...wardScope(), expected_row_version: handover.row_version })),
    },
  ));
}

export async function voidShiftHandover(
  lease: ContextLeaseWire,
  handover: ShiftHandoverWire,
  reason: string,
): Promise<ShiftHandoverWire> {
  return shiftHandoverWireSchema.parse(await request(
    `/shift-handovers/${handover.handover_id}/voids`, {
      method: 'POST',
      headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(shiftHandoverVoidRequestWireSchema.parse({
        ...wardScope(), expected_row_version: handover.row_version, reason,
      })),
    },
  ));
}

export async function listShiftHandoverPatients(lease: ContextLeaseWire, handoverId: string): Promise<ShiftHandoverPatientWire[]> {
  return shiftHandoverPatientWireSchema.array().parse(await request(
    `/shift-handover-patients?handover_id=${encodeURIComponent(handoverId)}`,
    { headers: wardHeaders(lease) },
  ));
}

export async function createShiftHandoverPatient(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').ShiftHandoverPatientCreateRequestWire, 'organization_id' | 'facility_id' | 'ward_id'>,
): Promise<ShiftHandoverPatientWire> {
  return shiftHandoverPatientWireSchema.parse(await request('/shift-handover-patients', {
    method: 'POST',
    headers: { ...wardHeaders(lease), 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(shiftHandoverPatientCreateRequestWireSchema.parse({ ...wardScope(), ...input })),
  }));
}

export async function listNursingDischargeClosures(lease: ContextLeaseWire): Promise<NursingDischargeClosureWire[]> {
  return nursingDischargeClosureWireSchema.array().parse(await request(
    `/nursing-discharge-closures?patient_id=${encodeURIComponent(clinicalContext.patientId)}`,
    { headers: patientOnlyHeaders(lease) },
  ));
}

export async function closeNursingDischarge(lease: ContextLeaseWire): Promise<NursingDischargeClosureWire> {
  return nursingDischargeClosureWireSchema.parse(await request('/nursing-discharge-closures', patientJson('POST', lease, nursingDischargeClosureRequestWireSchema.parse({ ...scoped() }))));
}

export async function listNursingBedsideNotes(lease: ContextLeaseWire): Promise<NursingBedsideNoteWire[]> {
  return nursingBedsideNoteWireSchema.array().parse(await request(
    `/nursing-bedside-notes?patient_id=${encodeURIComponent(clinicalContext.patientId)}`,
    { headers: patientOnlyHeaders(lease) },
  ));
}

export async function createNursingBedsideNote(
  lease: ContextLeaseWire,
  input: Omit<import('../generated/contracts').NursingBedsideNoteCreateRequestWire, 'organization_id' | 'facility_id' | 'patient_id' | 'encounter_id'>,
): Promise<NursingBedsideNoteWire> {
  return nursingBedsideNoteWireSchema.parse(await request('/nursing-bedside-notes', patientJson('POST', lease, nursingBedsideNoteCreateRequestWireSchema.parse({ ...scoped(), ...input }))));
}
