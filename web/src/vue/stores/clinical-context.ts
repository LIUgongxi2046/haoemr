import { defineStore } from 'pinia';
import { clinicalLeaveGate } from '../clinical-leave-guard';
import type { ContextLeaseWire } from '../../generated/contracts';
import { clinicalContext, selectOutpatientContext, selectOutpatientDocument } from '../../clinical-api';

export const useClinicalContextStore = defineStore('clinical-context', {
  state: () => ({
    leaseId: null as string | null,
    patientId: clinicalContext.patientId as string | null,
    encounterId: clinicalContext.encounterId as string | null,
    patientDisplayName: clinicalContext.patientDisplayName,
    documentId: clinicalContext.documentId as string | null,
    purposeCode: null as string | null,
    contextEpoch: 0,
  }),
  actions: {
    replaceFromLease(lease: ContextLeaseWire) {
      this.leaseId = lease.lease_id;
      this.patientId = lease.patient_id;
      this.encounterId = lease.encounter_id;
      this.purposeCode = lease.purpose_code;
      this.contextEpoch += 1;
    },
    async activateOutpatient(input: { patientId: string; encounterId: string; patientDisplayName: string; documentId?: string | null }) {
      if ((this.patientId !== input.patientId || this.encounterId !== input.encounterId || (input.documentId != null && this.documentId !== input.documentId)) && !await clinicalLeaveGate.confirm()) return false;
      selectOutpatientContext(input);
      this.leaseId = null;
      this.patientId = input.patientId;
      this.encounterId = input.encounterId;
      this.patientDisplayName = input.patientDisplayName;
      this.documentId = input.documentId ?? null;
      this.purposeCode = null;
      this.contextEpoch += 1;
      return true;
    },
    async activateDocument(documentId: string) {
      if (this.documentId !== documentId && !await clinicalLeaveGate.confirm()) return false;
      selectOutpatientDocument(documentId);
      this.documentId = documentId;
      this.contextEpoch += 1;
    },
    clear(reason = 'CONTEXT_DISPOSED') {
      this.leaseId = null;
      this.patientId = null;
      this.encounterId = null;
      this.patientDisplayName = '';
      this.documentId = null;
      this.purposeCode = null;
      this.contextEpoch += 1;
      return reason;
    },
  },
});
