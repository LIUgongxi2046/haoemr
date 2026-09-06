package org.haoemr.clinical;

import java.time.Instant;
import java.util.UUID;
import org.haoemr.security.ClinicalIdentity;

interface CorrectionPropagationProvider {
    DeliveryReceipt deliver(
            ClinicalIdentity identity,
            UUID documentId,
            UUID correctionId,
            UUID propagationId,
            String destinationCode,
            String contentHash,
            Instant requestedAt);

    record DeliveryReceipt(boolean delivered, String providerCode, String receiptRef, String errorCode) {
        static DeliveryReceipt delivered(String providerCode, String receiptRef) {
            return new DeliveryReceipt(true, providerCode, receiptRef, null);
        }

        static DeliveryReceipt failed(String providerCode, String errorCode) {
            return new DeliveryReceipt(false, providerCode, null, errorCode);
        }
    }
}
