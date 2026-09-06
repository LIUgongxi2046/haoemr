package org.haoemr.dictionary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DictionaryItemCreateRequestWire;
import org.haoemr.contracts.DictionaryItemDeactivateRequestWire;
import org.haoemr.contracts.DictionaryItemWire;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class DictionaryService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    DictionaryService(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    DictionaryItemWire createItem(
            ClinicalIdentity identity, String idempotencyKey, DictionaryItemCreateRequestWire request) {
        if (request.dictionaryCode() == null || request.dictionaryCode().isBlank()
                || request.itemCode() == null || request.itemCode().isBlank()
                || request.itemName() == null || request.itemName().isBlank()
                || request.effectiveFrom() == null) {
            throw invalid("dictionary_code, item_code, item_name and effective_from are required");
        }
        return transactions.execute(status -> {
            beginCommand(identity, "DICTIONARY_ITEM_CREATE", idempotencyKey,
                    sha256(request.dictionaryCode() + "|" + request.itemCode() + "|" + request.itemName()
                            + "|" + request.effectiveFrom()));
            UUID itemId = UUID.randomUUID();
            jdbc.sql("""
                    insert into dictionary_item(
                      tenant_id, dictionary_item_id, dictionary_code, item_code, item_name,
                      status, effective_from)
                    values (:tenant, :item, :dictionary_code, :item_code, :item_name, 'ACTIVE', :effective_from)
                    """).param("tenant", identity.tenantId()).param("item", itemId)
                    .param("dictionary_code", request.dictionaryCode()).param("item_code", request.itemCode())
                    .param("item_name", request.itemName().trim()).param("effective_from", request.effectiveFrom()).update();
            appendEvidence(identity, itemId, 1, "DICTIONARY_ITEM_CREATED", "DictionaryItemCreated");
            completeCommand(identity, "DICTIONARY_ITEM_CREATE", idempotencyKey, itemId);
            return item(identity.tenantId(), itemId);
        });
    }

    DictionaryItemWire deactivateItem(
            ClinicalIdentity identity, String idempotencyKey, UUID itemId,
            DictionaryItemDeactivateRequestWire request) {
        return transactions.execute(status -> {
            beginCommand(identity, "DICTIONARY_ITEM_DEACTIVATE", idempotencyKey,
                    sha256(itemId + "|" + request.expectedRowVersion()));
            ItemHead current = jdbc.sql("""
                    select status, row_version from dictionary_item
                    where tenant_id = :tenant and dictionary_item_id = :item for update
                    """).param("tenant", identity.tenantId()).param("item", itemId)
                    .query((rs, row) -> new ItemHead(rs.getString("status"), rs.getLong("row_version")))
                    .optional().orElseThrow(() -> contextDenied());
            if (request.expectedRowVersion() == null || current.rowVersion() != request.expectedRowVersion()) {
                throw new DictionaryException("DICTIONARY_ITEM_VERSION_CONFLICT", 409, "The dictionary item changed; reload before retrying");
            }
            if (!"ACTIVE".equals(current.status())) {
                throw new DictionaryException("DICTIONARY_ITEM_STATE_INVALID", 409, "Only an active dictionary item can be deactivated");
            }
            jdbc.sql("""
                    update dictionary_item set status = 'INACTIVE', effective_to = current_date,
                      row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenant and dictionary_item_id = :item and row_version = :expected
                    """).param("tenant", identity.tenantId()).param("item", itemId)
                    .param("expected", current.rowVersion()).update();
            appendEvidence(identity, itemId, current.rowVersion() + 1,
                    "DICTIONARY_ITEM_DEACTIVATED", "DictionaryItemDeactivated");
            completeCommand(identity, "DICTIONARY_ITEM_DEACTIVATE", idempotencyKey, itemId);
            return item(identity.tenantId(), itemId);
        });
    }

    List<DictionaryItemWire> listItems(ClinicalIdentity identity, String dictionaryCode) {
        return jdbc.sql("""
                select dictionary_item_id from dictionary_item
                where tenant_id = :tenant and dictionary_code = :code
                order by status, item_code, dictionary_item_id
                """).param("tenant", identity.tenantId()).param("code", dictionaryCode)
                .query(UUID.class).list().stream()
                .map(id -> item(identity.tenantId(), id)).toList();
    }

    private DictionaryItemWire item(UUID tenantId, UUID itemId) {
        return jdbc.sql("""
                select dictionary_item_id, dictionary_code, item_code, item_name, status,
                  effective_from, effective_to, row_version
                from dictionary_item where tenant_id = :tenant and dictionary_item_id = :item
                """).param("tenant", tenantId).param("item", itemId)
                .query((rs, row) -> new DictionaryItemWire(
                        rs.getObject("dictionary_item_id", UUID.class), rs.getString("dictionary_code"),
                        rs.getString("item_code"), rs.getString("item_name"),
                        DictionaryItemWire.StatusValue.valueOf(rs.getString("status")),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class), rs.getLong("row_version")))
                .optional().orElseThrow(() -> contextDenied());
    }

    private void beginCommand(ClinicalIdentity identity, String scope, String key, String requestHash) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new DictionaryException("INVALID_IDEMPOTENCY_KEY", 400, "A valid Idempotency-Key is required");
        }
        int inserted = jdbc.sql("""
                insert into idempotency_record(
                  tenant_id, command_scope, idempotency_key, request_hash, state, trace_id, expires_at)
                values (:tenant, :scope, :key, :hash, 'IN_PROGRESS', :trace, now() + interval '24 hours')
                on conflict (tenant_id, command_scope, idempotency_key) do nothing
                """).param("tenant", identity.tenantId()).param("scope", scope).param("key", key)
                .param("hash", requestHash).param("trace", UUID.randomUUID().toString()).update();
        if (inserted != 1) {
            throw new DictionaryException("IDEMPOTENCY_REPLAY", 409, "This command key was already used");
        }
    }

    private void completeCommand(ClinicalIdentity identity, String scope, String key, UUID itemId) {
        jdbc.sql("""
                update idempotency_record set state = 'SUCCEEDED', response_status = 200,
                  response_ref = jsonb_build_object('resource_id', :resource)
                where tenant_id = :tenant and command_scope = :scope and idempotency_key = :key
                """).param("resource", itemId).param("tenant", identity.tenantId())
                .param("scope", scope).param("key", key).update();
    }

    private void appendEvidence(ClinicalIdentity identity, UUID itemId, long version, String action, String eventType) {
        jdbc.sql("select tenant_id from tenant where tenant_id = :tenant for update")
                .param("tenant", identity.tenantId()).query(UUID.class).single();
        String previousHash = jdbc.sql("""
                select event_hash from audit_event where tenant_id = :tenant
                order by occurred_at desc, audit_event_id desc limit 1
                """).param("tenant", identity.tenantId()).query(String.class).optional().orElse(null);
        UUID auditId = UUID.randomUUID();
        String trace = UUID.randomUUID().toString();
        String eventHash = sha256(identity.tenantId() + "|" + auditId + "|" + action + "|"
                + itemId + "|" + trace + "|" + (previousHash == null ? "GENESIS" : previousHash));
        jdbc.sql("""
                insert into audit_event(
                  tenant_id, audit_event_id, occurred_at, actor_user_id, action_code,
                  resource_type, resource_id, patient_ref_hash, trace_id, previous_hash, event_hash)
                values (:tenant, :audit, now(), :actor, :action, 'DICTIONARY_ITEM', :resource,
                  null, :trace, :previous, :event_hash)
                """).param("tenant", identity.tenantId()).param("audit", auditId)
                .param("actor", identity.userId()).param("action", action).param("resource", itemId)
                .param("trace", trace).param("previous", previousHash).param("event_hash", eventHash).update();
        jdbc.sql("""
                insert into outbox_event(
                  tenant_id, event_id, aggregate_type, aggregate_id, aggregate_version,
                  event_type, schema_version, payload)
                values (:tenant, :event, 'DICTIONARY_ITEM', :aggregate, :version, :event_type, 1,
                  jsonb_build_object('resource_id', :aggregate))
                """).param("tenant", identity.tenantId()).param("event", UUID.randomUUID())
                .param("aggregate", itemId).param("version", version).param("event_type", eventType).update();
    }

    private static DictionaryException invalid(String message) {
        return new DictionaryException("DICTIONARY_ITEM_REQUEST_INVALID", 400, message);
    }

    static DictionaryException contextDenied() {
        return new DictionaryException("CONTEXT_NOT_PERMITTED", 403, "The requested dictionary context is not permitted");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ItemHead(String status, long rowVersion) {}
}
