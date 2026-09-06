package org.haoemr.dictionary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.haoemr.contracts.DictionaryItemCreateRequestWire;
import org.haoemr.contracts.DictionaryItemDeactivateRequestWire;
import org.haoemr.contracts.DictionaryItemWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev-synthetic")
final class DictionaryApiTest {

    private static final String TENANT = "018f0000-0000-7000-8000-00000000aa01";
    private static final String ORGANIZATION = "018f0000-0000-7000-8000-00000000aa02";
    private static final String FACILITY = "018f0000-0000-7000-8000-00000000aa03";
    private static final String USER = "018f0000-0000-7000-8000-00000000aa04";
    private static final String ROLE = "018f0000-0000-7000-8000-00000000aa05";

    @Autowired
    private DictionaryService dictionaries;

    @Autowired
    private JdbcClient jdbc;

    private final UUID tenant = UUID.fromString(TENANT);
    private final UUID organization = UUID.fromString(ORGANIZATION);
    private final UUID facility = UUID.fromString(FACILITY);

    private ClinicalIdentity identity() {
        return new ClinicalIdentity(tenant, UUID.fromString(USER), List.of(UUID.fromString(ROLE)));
    }

    @Test
    void givenDictionaryCode_whenCreatingListingAndDeactivatingItem_thenLifecycleRecorded() {
        String dictionaryCode = "DICT-" + UUID.randomUUID();
        DictionaryItemWire created = dictionaries.createItem(identity(), "dict-" + UUID.randomUUID(),
                new DictionaryItemCreateRequestWire(organization, facility, dictionaryCode,
                        "PO", "口服", LocalDate.now()));
        assertThat(created.status()).isEqualTo(DictionaryItemWire.StatusValue.ACTIVE);
        assertThat(created.itemCode()).isEqualTo("PO");

        List<DictionaryItemWire> listed = dictionaries.listItems(identity(), dictionaryCode);
        assertThat(listed).extracting(DictionaryItemWire::dictionaryItemId).contains(created.dictionaryItemId());

        DictionaryItemWire deactivated = dictionaries.deactivateItem(identity(), "deact-" + UUID.randomUUID(),
                created.dictionaryItemId(), new DictionaryItemDeactivateRequestWire(
                        organization, facility, created.rowVersion()));
        assertThat(deactivated.status()).isEqualTo(DictionaryItemWire.StatusValue.INACTIVE);
        assertThat(deactivated.effectiveTo()).isNotNull();
    }

    @Test
    void givenDuplicateItemCode_whenCreating_thenConstraintConflict() {
        String dictionaryCode = "DICT-" + UUID.randomUUID();
        dictionaries.createItem(identity(), "dict-" + UUID.randomUUID(),
                new DictionaryItemCreateRequestWire(organization, facility, dictionaryCode,
                        "IV", "静脉", LocalDate.now()));
        assertThatThrownBy(() -> dictionaries.createItem(identity(), "dict-" + UUID.randomUUID(),
                new DictionaryItemCreateRequestWire(organization, facility, dictionaryCode,
                        "IV", "静脉重复", LocalDate.now())))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void givenDictionaryItemCode_whenTampered_thenDatabaseRejectsMutation() {
        String dictionaryCode = "DICT-" + UUID.randomUUID();
        DictionaryItemWire created = dictionaries.createItem(identity(), "dict-" + UUID.randomUUID(),
                new DictionaryItemCreateRequestWire(organization, facility, dictionaryCode,
                        "SC", "皮下", LocalDate.now()));
        assertThatThrownBy(() -> jdbc.sql("""
                update dictionary_item set item_code = 'IM'
                where tenant_id = cast(:tenant as uuid) and dictionary_item_id = :item
                """).param("tenant", TENANT).param("item", created.dictionaryItemId()).update())
                .isInstanceOf(DataAccessException.class);
    }
}
