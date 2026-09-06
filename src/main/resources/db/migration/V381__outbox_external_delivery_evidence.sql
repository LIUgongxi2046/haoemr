-- Legacy receipts do not encode whether the consumer was an internal projection
-- or an external hospital-facing channel. Keep those rows NULL rather than
-- inventing historical delivery evidence; all receipts written after this
-- migration carry the consumer's declared kind.
alter table outbox_consumer_receipt
  add column delivery_kind varchar(48);

alter table outbox_consumer_receipt
  add constraint outbox_consumer_receipt_delivery_kind_check
    check (delivery_kind is null or delivery_kind in ('INTERNAL_PROJECTION', 'EXTERNAL_HOSPITAL_DELIVERY'));

create index outbox_consumer_receipt_external_delivery_idx
  on outbox_consumer_receipt (tenant_id, event_id)
  where delivery_kind = 'EXTERNAL_HOSPITAL_DELIVERY';
