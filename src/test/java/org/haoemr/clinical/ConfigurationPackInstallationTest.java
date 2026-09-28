package org.haoemr.clinical;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.haoemr.contracts.*;
import org.haoemr.security.ClinicalIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Integration tests retain evidence in their dedicated disposable database. No immutable triggers are disabled. */
final class ConfigurationPackInstallationTest {
    private JdbcClient jdbc; private TransactionTemplate tx; private DocumentTemplateService templates;
    private final ObjectMapper mapper=new ObjectMapper();
    private final UUID tenant=UUID.randomUUID(),org=UUID.randomUUID(),facility=UUID.randomUUID(),user=UUID.randomUUID(),role=UUID.randomUUID(),reviewer=UUID.randomUUID(),reviewRole=UUID.randomUUID();
    private ClinicalIdentity identity; private ConfigurationPackService packs; private String raw;
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("HAOEMR_INSTALL_DB_URL");assumeTrue(url!=null,"Dedicated installation DB required");
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/haoemr_iteration_[a-z_]+$");
        var source=new DriverManagerDataSource(url,System.getenv().getOrDefault("HAOEMR_DB_USER","liuhaoxian"),"");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=JdbcClient.create(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        templates=new DocumentTemplateService(jdbc,tx,mapper);raw=new ClassPathResource("configuration-packs/local-outpatient.json").getContentAsString(StandardCharsets.UTF_8);
        jdbc.sql("insert into tenant(tenant_id,tenant_code,display_name,status) values (:id,:code,'本地验收机构','ACTIVE')").param("id",tenant).param("code",tenant.toString()).update();
        jdbc.sql("insert into organization(tenant_id,organization_id,organization_code,display_name,status) values (:tenant,:id,'ITER','测试机构','ACTIVE')").param("tenant",tenant).param("id",org).update();
        jdbc.sql("insert into facility(tenant_id,organization_id,facility_id,facility_code,display_name,status) values (:tenant,:org,:id,'MAIN','测试院区','ACTIVE')").param("tenant",tenant).param("org",org).param("id",facility).update();
        account(user,role);account(reviewer,reviewRole);identity=new ClinicalIdentity(tenant,user,List.of(role));packs=service("");
    }
    private void account(UUID userId,UUID roleId){
        jdbc.sql("insert into app_user(tenant_id,user_id,external_subject,display_name,status) values (:tenant,:user,:subject,'合成管理员','ACTIVE')").param("tenant",tenant).param("user",userId).param("subject",userId.toString()).update();
        jdbc.sql("insert into role_assignment(tenant_id,role_assignment_id,user_id,organization_id,facility_id,role_code,valid_from,status) values (:tenant,:role,:user,:org,:facility,'SYSTEM_ADMIN',now()-interval '1 day','ACTIVE')")
                .param("tenant",tenant).param("role",roleId).param("user",userId).param("org",org).param("facility",facility).update();
    }
    private ConfigurationPackService service(String trusted){return new ConfigurationPackService(jdbc,tx,templates,new ConfigurationPackCodec(mapper),mapper,new MockEnvironment().withProperty("spring.profiles.active","dev-synthetic"),trusted,"0.1.0-SNAPSHOT");}
    private ConfigurationPackPrecheckWire check(String json,List<String> keep){return packs.precheck(identity,UUID.randomUUID().toString(),new ConfigurationPackPrecheckRequestWire(org,facility,json,keep,null));}
    private ConfigurationPackOperationWire install(ConfigurationPackPrecheckWire pre){return packs.install(identity,UUID.randomUUID().toString(),new ConfigurationPackInstallRequestWire(pre.precheckId(),null));}
    @Test void readsOnlyDeclaredCatalogFilesWithoutGrantingImplicitTrust() throws Exception {
        var file=java.nio.file.Files.createTempFile("haoemr-catalog-", ".json");
        try {
            java.nio.file.Files.writeString(file,raw);
            var env=new MockEnvironment().withProperty("spring.profiles.active","prod").withProperty("haoemr.configuration-packs.catalog-files",file.toString());
            var untrusted=new ConfigurationPackService(jdbc,tx,templates,new ConfigurationPackCodec(mapper),mapper,env,"","0.1.0-SNAPSHOT");
            var item=untrusted.catalog(identity).getFirst();assertThat(item.trusted()).isFalse();assertThat(item.compatible()).isTrue();assertThat(item.templateCount()).isEqualTo(3);
            var approved=new ConfigurationPackService(jdbc,tx,templates,new ConfigurationPackCodec(mapper),mapper,env,ConfigurationPackCodec.hash(raw),"other-version");
            assertThat(approved.catalog(identity).getFirst().trusted()).isTrue();assertThat(approved.catalog(identity).getFirst().compatible()).isFalse();
            env.withProperty("haoemr.configuration-packs.catalog-files",file+","+file);
            assertThat(approved.catalog(identity)).hasSize(1);
            java.nio.file.Files.writeString(file,"{invalid}");
            assertThatThrownBy(()->approved.catalog(identity)).isInstanceOf(ConfigurationPackException.class);
            env.withProperty("haoemr.configuration-packs.catalog-files","relative.json");
            assertThatThrownBy(()->approved.catalog(identity)).hasMessageContaining("不可读");
        } finally { java.nio.file.Files.deleteIfExists(file); }
    }
    @Test void importsDraftsOnceAndKeepsOriginalIndependentPublication() {
        var pre=check(raw,List.of());assertThat(pre.state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.READY);
        var op=install(pre);assertThat(op.status()).isEqualTo(ConfigurationPackOperationWire.StatusValue.SUCCEEDED);assertThat(op.importedCount()).isEqualTo(3);
        assertThat(install(pre).operationId()).isEqualTo(op.operationId());
        assertThat(check(raw,List.of()).issues()).anyMatch(x->x.contains("该版本已安装"));
        var chain=jdbc.sql("select event_hash,previous_hash from audit_event where tenant_id=:tenant order by occurred_at,audit_event_id")
            .param("tenant",tenant).query((rs,n)->new String[]{rs.getString(1),rs.getString(2)}).list();
        for(int n=1;n<chain.size();n++)assertThat(chain.get(n)[1]).as("event predecessor %s",n).isEqualTo(chain.get(n-1)[0]);
        var lastTemplateEvidence=jdbc.sql("select max(occurred_at) from audit_event where tenant_id=:tenant and action_code='DOCUMENT_TEMPLATE_CREATED'")
            .param("tenant",tenant).query(java.time.OffsetDateTime.class).single().toInstant();
        assertThat(op.finishedAt()).isAfterOrEqualTo(lastTemplateEvidence);
        var draft=templates.list(identity).getFirst();assertThat(draft.versionStatus()).isEqualTo("DRAFT");
        var command=new DocumentTemplateService.DocumentTemplateVersionPublishRequest(draft.versionRowVersion(),Instant.now());
        assertThatThrownBy(()->templates.publish(identity,"self-publish",draft.templateId(),draft.templateVersionId(),command)).isInstanceOf(DocumentTemplateException.class);
        assertThat(templates.publish(new ClinicalIdentity(tenant,reviewer,List.of(reviewRole)),"review-publish",draft.templateId(),draft.templateVersionId(),command).versionStatus()).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("select count(*) from patient where tenant_id=:tenant").param("tenant",tenant).query(Long.class).single()).isZero();
    }
    @Test void restoresHistoryAsDraftWithoutChangingPublishedOrHistoricalVersions() {
        install(check(raw,List.of()));
        var v1=templates.list(identity).getFirst();
        var approver=new ClinicalIdentity(tenant,reviewer,List.of(reviewRole));
        templates.publish(approver,"publish-v1",v1.templateId(),v1.templateVersionId(),
            new DocumentTemplateService.DocumentTemplateVersionPublishRequest(v1.versionRowVersion(),Instant.now()));
        var v2=templates.createVersion(identity,"hospital-v2",v1.templateId(),
            new DocumentTemplateService.DocumentTemplateVersionCreateRequest(v1.templateRowVersion(),v1.sectionSchema(),v1.requiredFields(),Map.of("layout","hospital")));
        templates.publish(approver,"publish-v2",v2.templateId(),v2.templateVersionId(),
            new DocumentTemplateService.DocumentTemplateVersionPublishRequest(v2.versionRowVersion(),Instant.now()));
        var latest=templates.list(identity).stream().filter(t->t.templateVersionId().equals(v2.templateVersionId())).findFirst().orElseThrow();
        var request=new DocumentTemplateService.DocumentTemplateVersionCreateRequest(latest.templateRowVersion(),v1.sectionSchema(),v1.requiredFields(),Map.of("tampered","ignored"),v1.templateVersionId());
        var restored=templates.createVersion(identity,"restore-v1",v1.templateId(),request);
        assertThat(restored.versionNo()).isEqualTo(3);assertThat(restored.versionStatus()).isEqualTo("DRAFT");
        assertThat(restored.sectionSchema()).isEqualTo(v1.sectionSchema());assertThat(restored.displayRules()).isEqualTo(v1.displayRules());
        assertThatThrownBy(()->templates.createVersion(identity,"restore-v1",v1.templateId(),request)).isInstanceOf(DocumentTemplateException.class).hasMessageContaining("already submitted");
        assertThat(templates.list(identity).stream().filter(t->t.templateVersionId().equals(v2.templateVersionId())).findFirst().orElseThrow().versionStatus()).isEqualTo("PUBLISHED");
        var publish=new DocumentTemplateService.DocumentTemplateVersionPublishRequest(restored.versionRowVersion(),Instant.now());
        assertThatThrownBy(()->templates.publish(identity,"restore-self-publish",restored.templateId(),restored.templateVersionId(),publish)).isInstanceOf(DocumentTemplateException.class);
        templates.publish(approver,"restore-review-publish",restored.templateId(),restored.templateVersionId(),publish);
        var history=templates.list(identity).stream().filter(t->t.templateId().equals(v1.templateId())).toList();
        assertThat(history).hasSize(3);
        assertThat(history.stream().filter(t->t.templateVersionId().equals(v1.templateVersionId())).findFirst().orElseThrow().sectionSchema()).isEqualTo(v1.sectionSchema());
        assertThat(history.stream().filter(t->t.templateVersionId().equals(v2.templateVersionId())).findFirst().orElseThrow().displayRules()).containsEntry("layout","hospital");
    }
    @Test void blocksUntrustedDemoDependenciesAndChangedSnapshotBeforeWriting() {
        String untrusted=raw.replace("1.0.0","2.0.0");assertThat(check(untrusted,List.of()).state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.BLOCKED);
        String demo=raw.replace("\"CONFIG\"","\"DEMO\"");packs=service(ConfigurationPackCodec.hash(demo));assertThat(check(demo,List.of()).issues()).contains("演示数据不通过配置安装入口导入");
        String dependency=raw.replace("\"dependencies\": []","\"dependencies\": [{\"code\":\"missing\",\"version\":\"1.0.0\"}]");
        packs=service(ConfigurationPackCodec.hash(dependency));assertThat(check(dependency,List.of()).issues()).anyMatch(x->x.contains("缺少依赖"));
        packs=service("");var stale=check(raw,List.of());
        templates.create(identity,"change",new DocumentTemplateService.DocumentTemplateCreateRequest(null,"other","其它模板","OTHER",org,facility,null,Map.of("type","object","properties",Map.of()),List.of(),Map.of()));
        assertThatThrownBy(()->install(stale)).isInstanceOf(ConfigurationPackException.class).hasMessageContaining("失效");
        assertThat(packs.list(identity,org,facility)).isEmpty();
    }
    @Test void refusesCrossScopeAndChangedIdempotencyPayload() {
        assertThatThrownBy(()->packs.precheck(identity,"x",new ConfigurationPackPrecheckRequestWire(UUID.randomUUID(),facility,raw,List.of(),null))).isInstanceOf(ConfigurationPackException.class);
        var request=new ConfigurationPackPrecheckRequestWire(org,facility,raw,List.of(),null);
        var a=packs.precheck(identity,"same",request);assertThat(packs.precheck(identity,"same",request).precheckId()).isEqualTo(a.precheckId());
        assertThatThrownBy(()->packs.precheck(identity,"same",new ConfigurationPackPrecheckRequestWire(org,facility,raw,List.of("local-opd-initial"),null))).hasMessageContaining("幂等键");
    }
    @Test void concurrentRequestsCreateOneEffect() {
        var pre=check(raw,List.of());
        var a=CompletableFuture.supplyAsync(()->install(pre));var b=CompletableFuture.supplyAsync(()->install(pre));
        assertThat(a.join().operationId()).isEqualTo(b.join().operationId());
        assertThat(templates.list(identity)).hasSize(3);
        assertThat(packs.list(identity,org,facility)).hasSize(1);
    }
    @Test void preservesHospitalRevisionAndRequiresExplicitKeep() {
        install(check(raw,List.of()));var current=templates.list(identity).stream().filter(t->t.templateCode().equals("local-opd-initial")).findFirst().orElseThrow();
        templates.createVersion(identity,"hospital-change",current.templateId(),new DocumentTemplateService.DocumentTemplateVersionCreateRequest(current.templateRowVersion(),current.sectionSchema(),current.requiredFields(),Map.of("layout","hospital")));
        String upgrade=raw.replace("1.0.0","2.0.0");packs=service(ConfigurationPackCodec.hash(upgrade));
        var conflict=check(upgrade,List.of());
        assertThat(conflict.state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.BLOCKED);
        var difference=conflict.differences().stream().filter(d->d.code().equals("local-opd-initial")).findFirst().orElseThrow();
        assertThat(difference.baseContent()).isNotBlank();
        assertThat(difference.localContent()).contains("hospital");
        var op=install(check(upgrade,List.of("local-opd-initial")));assertThat(op.retainedCount()).isEqualTo(1);assertThat(op.importedCount()).isEqualTo(2);
        assertThat(templates.list(identity).stream().filter(t->t.templateCode().equals("local-opd-initial"))).hasSize(2);
        String downgrade=raw.replace("1.0.0","0.9.0");packs=service(ConfigurationPackCodec.hash(downgrade));
        var blocked=check(downgrade,List.of("local-opd-initial"));
        assertThat(blocked.state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.BLOCKED);
        assertThat(blocked.issues()).anyMatch(x->x.contains("不能通过安装入口降级"));
    }
    @Test void explicitlyCreatesCandidateDraftWithoutOverwritingHospitalPublishedVersion() {
        install(check(raw,List.of()));
        var v1=templates.list(identity).stream().filter(t->t.templateCode().equals("local-opd-initial")).findFirst().orElseThrow();
        var hospital=templates.createVersion(identity,"hospital-custom",v1.templateId(),new DocumentTemplateService.DocumentTemplateVersionCreateRequest(v1.templateRowVersion(),v1.sectionSchema(),v1.requiredFields(),Map.of("layout","hospital-custom")));
        templates.publish(new ClinicalIdentity(tenant,reviewer,List.of(reviewRole)),"hospital-publish",hospital.templateId(),hospital.templateVersionId(),new DocumentTemplateService.DocumentTemplateVersionPublishRequest(hospital.versionRowVersion(),Instant.now()));
        String upgrade=raw.replace("1.0.0","2.0.0");packs=service(ConfigurationPackCodec.hash(upgrade));
        assertThat(check(upgrade,List.of()).state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.BLOCKED);
        var pre=packs.precheck(identity,"explicit-draft",new ConfigurationPackPrecheckRequestWire(org,facility,upgrade,List.of(),List.of("local-opd-initial")));
        assertThat(pre.state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.READY);
        assertThat(pre.differences()).anyMatch(d->d.code().equals("local-opd-initial")&&d.action()==ConfigurationPackDifferenceWire.ActionValue.UPDATE&&d.localContent().contains("hospital-custom"));
        assertThat(install(pre).importedCount()).isEqualTo(3);
        var chain=templates.list(identity).stream().filter(t->t.templateId().equals(v1.templateId())).toList();
        assertThat(chain).hasSize(3);
        assertThat(chain.stream().filter(t->t.templateVersionId().equals(hospital.templateVersionId())).findFirst().orElseThrow().versionStatus()).isEqualTo("PUBLISHED");
        assertThat(chain.stream().filter(t->t.versionNo()==3).findFirst().orElseThrow().versionStatus()).isEqualTo("DRAFT");
        assertThat(chain.stream().filter(t->t.versionNo()==2).findFirst().orElseThrow().displayRules()).containsEntry("layout","hospital-custom");
    }
    @Test void rejectsAmbiguousUnknownAndStaleDraftDecisions() {
        assertThatThrownBy(()->packs.precheck(identity,"overlap",new ConfigurationPackPrecheckRequestWire(org,facility,raw,List.of("local-opd-initial"),List.of("local-opd-initial")))).hasMessageContaining("重叠");
        assertThatThrownBy(()->packs.precheck(identity,"unknown",new ConfigurationPackPrecheckRequestWire(org,facility,raw,List.of(),List.of("unknown")))).hasMessageContaining("未知");
        assertThatThrownBy(()->packs.precheck(identity,"duplicate",new ConfigurationPackPrecheckRequestWire(org,facility,raw,List.of(),List.of("local-opd-initial","local-opd-initial")))).hasMessageContaining("重复");
        install(check(raw,List.of()));String upgrade=raw.replace("1.0.0","2.0.0");packs=service(ConfigurationPackCodec.hash(upgrade));
        var pre=packs.precheck(identity,"draft",new ConfigurationPackPrecheckRequestWire(org,facility,upgrade,List.of(),List.of("local-opd-initial")));
        var old=templates.list(identity).getFirst();
        templates.createVersion(identity,"change-after-decision",old.templateId(),new DocumentTemplateService.DocumentTemplateVersionCreateRequest(old.templateRowVersion(),old.sectionSchema(),old.requiredFields(),Map.of("layout","new-local")));
        assertThatThrownBy(()->install(pre)).hasMessageContaining("失效");
        assertThat(packs.list(identity,org,facility)).hasSize(1);
    }
    @Test void hidesOtherFacilityContentAndKeepsProductionTrustClosed() {
        UUID other=UUID.randomUUID();
        jdbc.sql("insert into facility(tenant_id,organization_id,facility_id,facility_code,display_name,status) values (:tenant,:org,:id,'OTHER','另一院区','ACTIVE')")
            .param("tenant",tenant).param("org",org).param("id",other).update();
        templates.create(identity,"other-scope",new DocumentTemplateService.DocumentTemplateCreateRequest(null,"local-opd-initial","其它院区专用","LOCAL_OPD_INITIAL",org,other,null,Map.of("type","object","properties",Map.of("other-facility-content",Map.of("type","string"))),List.of(),Map.of()));
        var pre=check(raw,List.of());
        assertThat(pre.state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.BLOCKED);
        assertThat(mapper.writeValueAsString(pre)).doesNotContain("other-facility-content");
        var prod=new ConfigurationPackService(jdbc,tx,templates,new ConfigurationPackCodec(mapper),mapper,new MockEnvironment().withProperty("spring.profiles.active","prod"),"","0.1.0-SNAPSHOT");
        assertThat(prod.catalog(identity)).isEmpty();
        assertThat(prod.precheck(identity,"prod",new ConfigurationPackPrecheckRequestWire(org,facility,raw,List.of(),null)).trusted()).isFalse();
    }
    @Test void recoversAbandonedAdmissionButNeverAnActiveTransaction() throws Exception {
        var pre=check(raw,List.of());UUID operation=UUID.randomUUID();
        jdbc.sql("""
            insert into configuration_pack_operation(tenant_id,operation_id,organization_id,facility_id,precheck_id,requested_by,idempotency_key,request_hash,pack_code,pack_version,manifest_hash,status,created_at)
            values (:tenant,:id,:org,:facility,:pre,:user,'interrupted',:hash,'haoemr-local-outpatient','1.0.0',:hash,'RUNNING',now()-interval '3 minutes')
            """).param("tenant",tenant).param("id",operation).param("org",org).param("facility",facility).param("pre",pre.precheckId()).param("user",user).param("hash",ConfigurationPackCodec.hash(raw)).update();
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var worker=CompletableFuture.runAsync(()->tx.executeWithoutResult(unused->{
            jdbc.sql("select tenant_id from tenant where tenant_id=:tenant for update").param("tenant",tenant).query(UUID.class).single();locked.countDown();
            try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test lock timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
        }));
        try {assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(packs.reconcile(identity,operation).status()).isEqualTo(ConfigurationPackOperationWire.StatusValue.RUNNING);}
        finally {release.countDown();worker.join();}
        var recovered=packs.reconcile(identity,operation);assertThat(recovered.status()).isEqualTo(ConfigurationPackOperationWire.StatusValue.FAILED_ROLLED_BACK);
        assertThat(packs.reconcile(identity,operation)).isEqualTo(recovered);
        assertThat(templates.list(identity)).isEmpty();
    }
    @Test void thirtyItemFiveAdministratorPerformanceSmoke() throws Exception {
        var document=mapper.readTree(raw).deepCopy();
        var items=mapper.createArrayNode();
        for(int n=0;n<30;n++) {
            var item=mapper.createObjectNode().put("code","smoke-"+n).put("name","本地性能结构 "+n).put("document_type","SMOKE_"+n);
            item.set("fields",mapper.valueToTree(List.of("summary")));item.set("required_fields",mapper.valueToTree(List.of("summary")));items.add(item);
        }
        ((tools.jackson.databind.node.ObjectNode)document).set("templates",items);
        String manifest=mapper.writeValueAsString(document);packs=service(ConfigurationPackCodec.hash(manifest));
        var administrators=new java.util.ArrayList<ClinicalIdentity>();administrators.add(identity);
        for(int n=1;n<5;n++){UUID u=UUID.randomUUID(),r=UUID.randomUUID();account(u,r);administrators.add(new ClinicalIdentity(tenant,u,List.of(r)));}
        var preTimes=new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var checked=administrators.stream().map(admin->CompletableFuture.supplyAsync(()->{
            long start=System.nanoTime();var result=packs.precheck(admin,UUID.randomUUID().toString(),new ConfigurationPackPrecheckRequestWire(org,facility,manifest,List.of(),null));
            preTimes.add((System.nanoTime()-start)/1_000_000);assertThat(result.state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.READY);return result;
        })).toList().stream().map(CompletableFuture::join).toList();
        var installTimes=new java.util.concurrent.CopyOnWriteArrayList<Long>();var futures=new java.util.ArrayList<CompletableFuture<ConfigurationPackOperationWire>>();
        for(int n=0;n<5;n++){int index=n;futures.add(CompletableFuture.supplyAsync(()->{
            long start=System.nanoTime();var result=packs.install(administrators.get(index),UUID.randomUUID().toString(),new ConfigurationPackInstallRequestWire(checked.get(index).precheckId(),null));
            // Duplicate submissions may correctly return the existing RUNNING operation; measure until its terminal result.
            while(result.status()==ConfigurationPackOperationWire.StatusValue.RUNNING && System.nanoTime()-start<30_000_000_000L) {
                try {Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                result=packs.get(administrators.get(index),result.operationId());
            }
            installTimes.add((System.nanoTime()-start)/1_000_000);return result;
        }));}
        var results=futures.stream().map(CompletableFuture::join).toList();
        assertThat(results.stream().map(ConfigurationPackOperationWire::operationId).distinct()).hasSize(1);
        assertThat(results).allMatch(x->x.status()==ConfigurationPackOperationWire.StatusValue.SUCCEEDED&&x.importedCount()==30);
        assertThat(templates.list(identity)).hasSize(30);
        java.nio.file.Files.writeString(java.nio.file.Path.of("output/iteration-20260910-continuation/17-performance-smoke.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("layer","service + PostgreSQL; not HTTP or full NFR certification","items",30,"administrators",5,"manifest_bytes",manifest.getBytes(StandardCharsets.UTF_8).length,"precheck_ms",preTimes,"install_ms",installTimes)));
        assertThat(preTimes).allMatch(ms->ms<5000);assertThat(installTimes).allMatch(ms->ms<30000);
    }
    @Test void installsFiveHundredTemplatesFromAnExactTenMegabyteManifest() throws Exception {
        var document=mapper.readTree(raw).deepCopy();var items=mapper.createArrayNode();
        var fields=new java.util.ArrayList<String>();for(int f=0;f<100;f++)fields.add("f"+"x".repeat(92)+String.format("%03d",f));
        for(int n=0;n<500;n++) {
            var item=mapper.createObjectNode().put("code","capacity-"+n).put("name","容量验收模板 "+n).put("document_type","CAPACITY_"+n);
            item.set("fields",mapper.valueToTree(fields));item.set("required_fields",mapper.valueToTree(fields));items.add(item);
        }
        ((tools.jackson.databind.node.ObjectNode)document).set("templates",items);
        String json=mapper.writeValueAsString(document);int semanticBytes=json.getBytes(StandardCharsets.UTF_8).length;
        assertThat(semanticBytes).isLessThanOrEqualTo(ConfigurationPackCodec.MAX_BYTES);
        String manifest=json+" ".repeat(ConfigurationPackCodec.MAX_BYTES-semanticBytes);packs=service(ConfigurationPackCodec.hash(manifest));
        long start=System.nanoTime();var pre=check(manifest,List.of());long precheckMs=(System.nanoTime()-start)/1_000_000;
        assertThat(pre.state()).isEqualTo(ConfigurationPackPrecheckWire.StateValue.READY);
        start=System.nanoTime();var operation=install(pre);long installMs=(System.nanoTime()-start)/1_000_000;
        assertThat(operation.status()).isEqualTo(ConfigurationPackOperationWire.StatusValue.SUCCEEDED);assertThat(operation.importedCount()).isEqualTo(500);
        assertThat(templates.list(identity)).hasSize(500).allMatch(t->t.versionStatus().equals("DRAFT")&&t.requiredFields().size()==100);
        java.nio.file.Files.writeString(java.nio.file.Path.of("output/iteration-20260910-continuation/17-capacity-regression.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("layer","service and PostgreSQL, not HTTP p95","templates",500,"fields_per_template",100,"semantic_bytes",semanticBytes,"manifest_bytes",manifest.getBytes(StandardCharsets.UTF_8).length,"precheck_ms",precheckMs,"install_ms",installMs,"all_draft",true)));
        assertThat(precheckMs).isLessThan(5000);assertThat(installMs).isLessThan(30000);
    }
    @Test void failureEvidenceOutageNeverPretendsRollbackWasRecordedOrCreatesDuplicate() {
        String name="iteration_fail_evidence_"+tenant.toString().replace("-","");
        jdbc.sql("create function "+name+"() returns trigger language plpgsql as $$ begin if new.tenant_id='"+tenant+"'::uuid and new.action_code in ('DOCUMENT_TEMPLATE_CREATED','CONFIG_PACK_INSTALL_FAILED') then raise exception 'injected evidence outage'; end if; return new; end $$").update();
        jdbc.sql("create trigger "+name+" before insert on audit_event for each row execute function "+name+"()").update();
        var pre=check(raw,List.of());
        try {
            assertThatThrownBy(()->install(pre)).isInstanceOf(RuntimeException.class);
            var pending=packs.list(identity,org,facility);assertThat(pending).hasSize(1);
            assertThat(pending.getFirst().status()).isEqualTo(ConfigurationPackOperationWire.StatusValue.RUNNING);
            assertThat(pending.getFirst().finishedAt()).isNull();assertThat(templates.list(identity)).isEmpty();
            assertThat(install(pre).operationId()).isEqualTo(pending.getFirst().operationId());
            assertThat(packs.list(identity,org,facility)).hasSize(1);
        } finally {jdbc.sql("drop trigger "+name+" on audit_event").update();jdbc.sql("drop function "+name+"()").update();}
    }
    @Test void actualDatabaseConnectionLossRollsBackTheWholePack() throws Exception {
        String name="iteration_disconnect_"+tenant.toString().replace("-","");int lockKey=tenant.hashCode() & 0x7fffffff;
        jdbc.sql("create function "+name+"() returns trigger language plpgsql as $$ begin if new.tenant_id='"+tenant+"'::uuid and new.template_code='local-opd-review' then perform pg_advisory_xact_lock("+lockKey+"); perform pg_sleep(20); end if; return new; end $$").update();
        jdbc.sql("create trigger "+name+" before insert on clinical_document_template for each row execute function "+name+"()").update();
        var pre=check(raw,List.of());var running=CompletableFuture.supplyAsync(()->install(pre));
        try {
            Integer pid=null;long until=System.nanoTime()+10_000_000_000L;
            while(pid==null&&System.nanoTime()<until) {
                pid=jdbc.sql("select l.pid from pg_locks l join pg_stat_activity a on a.pid=l.pid where l.locktype='advisory' and l.objid=:key and a.datname=current_database() and l.pid<>pg_backend_pid()")
                    .param("key",lockKey).query(Integer.class).optional().orElse(null);
                if(pid==null)Thread.sleep(25);
            }
            assertThat(pid).as("the unique test transaction reached its injected blocking point").isNotNull();
            assertThat(jdbc.sql("select pg_terminate_backend(:pid)").param("pid",pid).query(Boolean.class).single()).isTrue();
            var failed=running.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(failed.status()).isEqualTo(ConfigurationPackOperationWire.StatusValue.FAILED_ROLLED_BACK);
            assertThat(templates.list(identity)).isEmpty();assertThat(failed.templateVersionIds()).isEmpty();
            assertThat(jdbc.sql("select count(*) from audit_event where tenant_id=:tenant and action_code='CONFIG_PACK_INSTALL_FAILED'").param("tenant",tenant).query(Long.class).single()).isEqualTo(1);
        } finally { running.get(25,java.util.concurrent.TimeUnit.SECONDS);jdbc.sql("drop trigger "+name+" on clinical_document_template").update();jdbc.sql("drop function "+name+"()").update(); }
    }
    @Test void restorationKeepsFrozenFormBindingAndRejectsForeignHistory() {
        UUID config=UUID.randomUUID();
        jdbc.sql("insert into config_item(tenant_id,config_id,config_type,config_key,display_name,payload,status,published_at) values (:tenant,:id,'FORM_TEMPLATE','runtime-restore-form','恢复测试表单',cast(:payload as jsonb),'ACTIVE',now())")
            .param("tenant",tenant).param("id",config).param("payload",mapper.writeValueAsString(Map.of("fields",List.of(Map.of("id","summary","required",true))))).update();
        var schema=Map.<String,Object>of("type","object","properties",Map.of("summary",Map.of("type","string")));
        var v1=templates.create(identity,"form-v1",new DocumentTemplateService.DocumentTemplateCreateRequest(null,"restore-form","恢复测试","RESTORE_FORM",org,facility,null,schema,List.of(),Map.of("form_config_key","restore-form")));
        jdbc.sql("update config_item set payload=cast(:payload as jsonb),row_version=row_version+1 where tenant_id=:tenant and config_id=:id").param("tenant",tenant).param("id",config)
            .param("payload",mapper.writeValueAsString(Map.of("fields",List.of(Map.of("id","new_required","required",true))))).update();
        var v2=templates.createVersion(identity,"form-v2",v1.templateId(),new DocumentTemplateService.DocumentTemplateVersionCreateRequest(v1.templateRowVersion(),schema,List.of(),Map.of()));
        var v3=templates.createVersion(identity,"restore-form",v1.templateId(),new DocumentTemplateService.DocumentTemplateVersionCreateRequest(v2.templateRowVersion(),schema,List.of(),Map.of(),v1.templateVersionId()));
        assertThat(v3.displayRules()).isEqualTo(v1.displayRules());assertThat(v3.requiredFields()).containsExactly("summary");
        var other=templates.create(identity,"other-form",new DocumentTemplateService.DocumentTemplateCreateRequest(null,"other-form","其它测试","OTHER_FORM",org,facility,null,schema,List.of(),Map.of()));
        assertThatThrownBy(()->templates.createVersion(identity,"foreign-history",other.templateId(),new DocumentTemplateService.DocumentTemplateVersionCreateRequest(other.templateRowVersion(),schema,List.of(),Map.of(),v1.templateVersionId()))).hasMessageContaining("same template");
    }
    @Test void middleFailureRollsBackTemplatesButKeepsFailureEvidence() {
        // Constraint-based fault injection scoped to this unique test tenant; never relax a production constraint.
        String function="iteration_fail_"+tenant.toString().replace("-","");
        jdbc.sql("create function "+function+"() returns trigger language plpgsql as $$ begin if new.tenant_id='"+tenant+"'::uuid and new.template_code='local-opd-review' then raise exception 'injected interruption'; end if; return new; end $$").update();
        jdbc.sql("create trigger "+function+" before insert on clinical_document_template for each row execute function "+function+"()").update();
        ConfigurationPackOperationWire failed;
        try { failed=install(check(raw,List.of())); }
        finally {jdbc.sql("drop trigger "+function+" on clinical_document_template").update();jdbc.sql("drop function "+function+"()").update();}
        assertThat(failed.status()).isEqualTo(ConfigurationPackOperationWire.StatusValue.FAILED_ROLLED_BACK);
        assertThat(templates.list(identity)).isEmpty();assertThat(failed.templateVersionIds()).isEmpty();
        assertThat(jdbc.sql("select count(*) from audit_event where tenant_id=:tenant and action_code='CONFIG_PACK_INSTALL_FAILED'").param("tenant",tenant).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(()->jdbc.sql("delete from configuration_pack_operation where tenant_id=:tenant").param("tenant",tenant).update()).isInstanceOf(RuntimeException.class);
        var pre=check(raw,List.of());var recovered=packs.install(identity,"recover",new ConfigurationPackInstallRequestWire(pre.precheckId(),failed.operationId()));
        assertThat(recovered.status()).isEqualTo(ConfigurationPackOperationWire.StatusValue.SUCCEEDED);assertThat(recovered.retryOf()).isEqualTo(failed.operationId());
    }
}
