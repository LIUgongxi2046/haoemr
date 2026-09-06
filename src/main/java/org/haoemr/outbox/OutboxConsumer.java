package org.haoemr.outbox;

/**
 * An ordered outbox consumer. Internal projections keep their effects in the
 * caller transaction; an external hospital adapter must submit the stable event id idempotently,
 * so a persisted consumer receipt is evidence of channel acceptance rather than a local projection.
 */
public interface OutboxConsumer {

    /**
     * Local projections make an event queryable but are not evidence that a hospital-facing
     * channel has accepted it. Only an explicitly declared external consumer may close an outbox event.
     */
    default DeliveryKind deliveryKind() {
        return DeliveryKind.INTERNAL_PROJECTION;
    }

    String consumerName();

    void consume(OutboxMessage message);

    enum DeliveryKind {
        INTERNAL_PROJECTION,
        EXTERNAL_HOSPITAL_DELIVERY
    }
}
