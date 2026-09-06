package org.haoemr.security;

import org.haoemr.contracts.ContextLeaseWire;

final class ContextLeaseWireMapper {

    private ContextLeaseWireMapper() {}

    static ContextLeaseWire toWire(ContextLease lease) {
        return new ContextLeaseWire(
                lease.leaseId(),
                lease.tenantId(),
                lease.organizationId(),
                lease.facilityId(),
                lease.userId(),
                lease.roleAssignmentIds(),
                lease.patientId(),
                lease.encounterId(),
                lease.taskId(),
                lease.purposeCode(),
                lease.allowedSourceTypes().stream()
                        .map(ContextLeaseWire.AllowedSourceTypesItemValue::valueOf)
                        .toList(),
                null,
                null,
                lease.authorizationWatermark(),
                ContextLeaseWire.DataClassificationCeilingValue.valueOf(lease.dataClassificationCeiling()),
                ContextLeaseWire.ModelResidencyPolicyValue.valueOf(lease.modelResidencyPolicy()),
                lease.expiresAt());
    }
}
