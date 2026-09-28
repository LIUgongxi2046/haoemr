package org.haoemr.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.haoemr.contracts.KnowledgeSearchRequestWire;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

final class KnowledgeCitationTest {
    private JdbcClient jdbc;
    private KnowledgeSearchService service;
    private final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
    private final ClinicalIdentity identity = new ClinicalIdentity(tenant, user, List.of());

    @BeforeEach void setup() {
        String url = System.getenv("HAOEMR_INSTALL_DB_URL");
        assumeTrue(url != null, "Dedicated local iteration database required");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/haoemr_iteration_[a-z_]+$");
        var source = new DriverManagerDataSource(url, System.getenv().getOrDefault("HAOEMR_DB_USER", "liuhaoxian"), "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(source);
        service = new KnowledgeSearchService(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)));
        jdbc.sql("insert into tenant(tenant_id,tenant_code,display_name,status) values (:id,:code,'引用本地验收','ACTIVE')")
            .param("id", tenant).param("code", tenant.toString()).update();
        jdbc.sql("insert into app_user(tenant_id,user_id,external_subject,display_name,status) values (:tenant,:user,:subject,'合成核对用户','ACTIVE')")
            .param("tenant", tenant).param("user", user).param("subject", user.toString()).update();
    }

    private UUID seed(String classification, String status, String documentStart, String documentEnd, String versionStart, String versionEnd) {
        UUID document = UUID.randomUUID(), version = UUID.randomUUID(), chunk = UUID.randomUUID();
        jdbc.sql("""
                insert into knowledge_document(tenant_id,document_id,document_code,content_type,title,classification,effective_from,effective_to)
                values (:tenant,:document,:code,'GUIDELINE','引用核对测试',:classification,cast(:start as timestamptz),cast(:end as timestamptz))
                """).param("tenant", tenant).param("document", document).param("code", document.toString())
                .param("classification", classification).param("start", documentStart).param("end", documentEnd).update();
        jdbc.sql("""
                insert into knowledge_document_version(tenant_id,doc_version_id,document_id,version,content_hash,markdown,status,effective_from,effective_to)
                values (:tenant,:version,:document,'v1',:hash,'仅供软件验收的非临床文字',:status,cast(:start as timestamptz),cast(:end as timestamptz))
                """).param("tenant", tenant).param("version", version).param("document", document)
                .param("hash", "a".repeat(64)).param("status", status).param("start", versionStart).param("end", versionEnd).update();
        jdbc.sql("""
                insert into knowledge_chunk(tenant_id,chunk_id,doc_version_id,section_path,text,content_hash)
                values (:tenant,:chunk,:version,'说明/适用范围','引用核对测试片段',:hash)
                """).param("tenant", tenant).param("chunk", chunk).param("version", version).param("hash", "b".repeat(64)).update();
        return chunk;
    }

    @Test void exactActiveCitationIncludesFrozenVersionSectionAndEffectiveScope() {
        UUID chunk = seed("INTERNAL", "ACTIVE", "2020-01-01T00:00:00Z", null, "2021-01-01T00:00:00Z", "2100-01-01T00:00:00Z");
        var result = service.citation(identity, chunk);
        assertThat(result.chunkId()).isEqualTo(chunk);
        assertThat(result.version()).isEqualTo("v1");
        assertThat(result.sectionPath()).isEqualTo("说明/适用范围");
        assertThat(result.text()).isEqualTo("引用核对测试片段");
        assertThat(result.contentHash()).isEqualTo("a".repeat(64));
        assertThat(result.effectiveFrom()).isEqualTo(java.time.Instant.parse("2021-01-01T00:00:00Z"));
        assertThat(result.effectiveTo()).isEqualTo(java.time.Instant.parse("2100-01-01T00:00:00Z"));
        assertThat(service.search(identity, new KnowledgeSearchRequestWire(null,null,"引用核对测试",null,null,20)).references()).hasSize(1);
        assertUnavailable(new ClinicalIdentity(UUID.randomUUID(), user, List.of()), chunk);
        jdbc.sql("update knowledge_document_version set status='RETIRED' where tenant_id=:tenant and doc_version_id=:version")
            .param("tenant", tenant).param("version", result.docVersionId()).update();
        assertUnavailable(identity, chunk);
    }

    @Test void searchAndOpenBothRejectRestrictedFutureExpiredRetiredAndMissingSources() {
        for (String sensitivity : List.of("SENSITIVE", "RESTRICTED")) assertUnavailable(identity, seed(sensitivity,"ACTIVE",null,null,null,null));
        for (String state : List.of("DRAFT", "IN_REVIEW", "RETIRED")) assertUnavailable(identity, seed("PUBLIC",state,null,null,null,null));
        assertUnavailable(identity, seed("INTERNAL","ACTIVE","2100-01-01T00:00:00Z",null,null,null));
        assertUnavailable(identity, seed("INTERNAL","ACTIVE",null,"2020-01-01T00:00:00Z",null,null));
        assertUnavailable(identity, seed("INTERNAL","ACTIVE",null,null,"2100-01-01T00:00:00Z",null));
        assertUnavailable(identity, seed("INTERNAL","ACTIVE",null,null,null,"2020-01-01T00:00:00Z"));
        assertUnavailable(identity, UUID.randomUUID());
        assertThat(service.search(identity, new KnowledgeSearchRequestWire(null,null,"引用核对测试",null,null,20)).references()).isEmpty();
    }

    private void assertUnavailable(ClinicalIdentity actor, UUID chunk) {
        assertThatThrownBy(() -> service.citation(actor, chunk)).isInstanceOfSatisfying(KnowledgeException.class, e -> {
            assertThat(e.status()).isEqualTo(404); assertThat(e.code()).isEqualTo("KNOWLEDGE_SOURCE_UNAVAILABLE");
        });
    }
}
