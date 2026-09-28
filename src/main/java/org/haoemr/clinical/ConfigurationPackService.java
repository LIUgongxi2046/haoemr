package org.haoemr.clinical;

import static org.haoemr.clinical.ConfigurationPackCodec.hash;
import static org.haoemr.clinical.ConfigurationPackCodec.invalid;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.haoemr.contracts.*;
import org.haoemr.security.ClinicalIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The installation coordinator adds provenance/recovery; all template writes use the existing domain service. */
@Service
final class ConfigurationPackService {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final DocumentTemplateService templates;
    private final ConfigurationPackCodec codec;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final String trustedHashes;
    private final String appVersion;

    ConfigurationPackService(JdbcClient jdbc, TransactionTemplate tx, DocumentTemplateService templates,
            ConfigurationPackCodec codec, ObjectMapper mapper, Environment environment,
            @Value("${haoemr.configuration-packs.trusted-sha256:}") String trustedHashes,
            @Value("${haoemr.version:0.1.0-SNAPSHOT}") String appVersion) {
        this.jdbc=jdbc; this.tx=tx; this.templates=templates; this.codec=codec;
        this.mapper=mapper; this.environment=environment; this.trustedHashes=trustedHashes; this.appVersion=appVersion;
    }

    List<ConfigurationPackCatalogItemWire> catalog(ClinicalIdentity identity) {
        templates.list(identity); // Reuse the existing administrator gate, also when no packages are configured.
        var documents = new ArrayList<String>();
        if (localFixtureEnabled()) documents.add(localFixture());
        String configured = environment.getProperty("haoemr.configuration-packs.catalog-files", "");
        if (!configured.isBlank()) {
            String[] paths = configured.split(",");
            if (paths.length > 100) throw invalid("配置目录最多声明 100 个文件");
            for (String path : paths) {
                try {
                    var file = java.nio.file.Path.of(path.trim());
                    if (!file.isAbsolute() || !java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) > ConfigurationPackCodec.MAX_BYTES)
                        throw invalid("部署配置目录包含不可读或超限文件");
                    documents.add(java.nio.file.Files.readString(file, StandardCharsets.UTF_8));
                } catch (java.io.IOException failure) { throw invalid("部署配置目录文件读取失败，请联系配置管理员"); }
            }
        }
        var result = new java.util.LinkedHashMap<String,ConfigurationPackCatalogItemWire>();
        for (String raw : documents) {
            var manifest = codec.parse(raw);
            var item = new ConfigurationPackCatalogItemWire(manifest.code(),manifest.version(),manifest.name(),manifest.publisher(),manifest.license(),hash(raw),trusted(raw),raw,
                ConfigurationPackCatalogItemWire.TypeValue.valueOf(manifest.type()),manifest.appVersion(),appVersion.equals(manifest.appVersion()),manifest.templates().size());
            var existing = result.putIfAbsent(manifest.code()+"@"+manifest.version(),item);
            if (existing!=null && !existing.manifestHash().equals(item.manifestHash())) throw invalid("目录内同一配置包版本存在不同内容，请核对发布来源");
        }
        return List.copyOf(result.values());
    }

    ConfigurationPackPrecheckWire precheck(ClinicalIdentity identity, String key, ConfigurationPackPrecheckRequestWire request) {
        validKey(key); if (request == null) throw invalid("缺少预检请求");
        authorize(identity, request.organizationId(), request.facilityId());
        var manifest=codec.parse(request.manifestJson());
        List<String> keep=request.keepCodes()==null ? List.of() : request.keepCodes();
        if (keep.size()!=new HashSet<>(keep).size() || !manifest.templates().stream().map(ConfigurationPackTemplateWire::code).toList().containsAll(keep))
            throw invalid("保留决策包含未知或重复条目");
        List<String> drafts=request.draftCodes()==null ? List.of() : request.draftCodes();
        if (drafts.size()!=new HashSet<>(drafts).size() || !manifest.templates().stream().map(ConfigurationPackTemplateWire::code).toList().containsAll(drafts)
                || drafts.stream().anyMatch(keep::contains)) throw invalid("新草稿决策包含未知、重复或与保留重叠的条目");
        String requestHash=hash(codec.json(request));
        return tx.execute(unused -> {
            lockTenant(identity);
            var existing=jdbc.sql("select request_hash,response::text from configuration_pack_precheck where tenant_id=:tenant and requested_by=:user and idempotency_key=:key")
                    .param("tenant",identity.tenantId()).param("user",identity.userId()).param("key",key)
                    .query((rs,n)->new String[]{rs.getString(1),rs.getString(2)}).optional();
            if(existing.isPresent()) {
                if(!existing.get()[0].equals(requestHash)) throw conflict("同一幂等键不能用于不同预检内容");
                return mapper.readValue(existing.get()[1],ConfigurationPackPrecheckWire.class);
            }
            var diff=differences(identity,request.organizationId(),request.facilityId(),manifest,keep,drafts);
            List<String> issues=issues(identity,request.organizationId(),request.facilityId(),manifest,request.manifestJson(),diff);
            UUID id=UUID.randomUUID(); Instant expires=Instant.now().plusSeconds(900);
            var response=new ConfigurationPackPrecheckWire(id,request.organizationId(),request.facilityId(),manifest.code(),manifest.version(),hash(request.manifestJson()),
                    trusted(request.manifestJson()),issues.isEmpty()?ConfigurationPackPrecheckWire.StateValue.READY:ConfigurationPackPrecheckWire.StateValue.BLOCKED,expires,issues,diff);
            jdbc.sql("""
                    insert into configuration_pack_precheck(tenant_id,precheck_id,organization_id,facility_id,requested_by,
                      idempotency_key,request_hash,manifest_json,manifest_hash,keep_codes,target_snapshot,response,expires_at)
                    values (:tenant,:id,:org,:facility,:user,:key,:request,:raw,:hash,cast(:keep as jsonb),:snapshot,cast(:response as jsonb),:expires)
                    """).param("tenant",identity.tenantId()).param("id",id).param("org",request.organizationId()).param("facility",request.facilityId())
                    .param("user",identity.userId()).param("key",key).param("request",requestHash).param("raw",request.manifestJson())
                    .param("hash",hash(request.manifestJson())).param("keep",codec.json(keep)).param("snapshot",snapshot(identity))
                    .param("response",codec.json(response)).param("expires",OffsetDateTime.ofInstant(expires,java.time.ZoneOffset.UTC)).update();
            evidence(identity,"CONFIG_PACK_PRECHECKED",id,Map.of("pack_code",manifest.code(),"pack_version",manifest.version(),"state",response.state().name()));
            return response;
        });
    }

    ConfigurationPackOperationWire install(ClinicalIdentity identity,String key,ConfigurationPackInstallRequestWire request) {
        validKey(key); if(request==null || request.precheckId()==null) throw invalid("缺少预检编号");
        var pre=loadPrecheck(identity,request.precheckId());
        authorize(identity,pre.response().organizationId(),pre.response().facilityId());
        String requestHash=hash(codec.json(request));
        var admitted=tx.execute(unused -> {
            lockTenant(identity);
            var replay=jdbc.sql("select operation_id,request_hash from configuration_pack_operation where tenant_id=:tenant and idempotency_key=:key")
                    .param("tenant",identity.tenantId()).param("key",key).query((rs,n)->new String[]{rs.getString(1),rs.getString(2)}).optional();
            if(replay.isPresent()) {
                if(!requestHash.equals(replay.get()[1])) throw conflict("同一幂等键不能提交不同安装");
                return new Admission(get(identity,UUID.fromString(replay.get()[0])),false);
            }
            var duplicate=jdbc.sql("""
                    select operation_id,manifest_hash from configuration_pack_operation where tenant_id=:tenant
                    and organization_id=:org and facility_id=:facility and pack_code=:code and pack_version=:version
                    and status in ('RUNNING','SUCCEEDED','RECOVERY_REQUIRED')
                    """).param("tenant",identity.tenantId()).param("org",pre.response().organizationId()).param("facility",pre.response().facilityId())
                    .param("code",pre.response().packCode()).param("version",pre.response().packVersion())
                    .query((rs,n)->new String[]{rs.getString(1),rs.getString(2)}).optional();
            if(duplicate.isPresent()) {
                if(!duplicate.get()[1].equals(pre.response().manifestHash())) throw conflict("同一包版本内容变化，请使用新的版本号");
                return new Admission(get(identity,UUID.fromString(duplicate.get()[0])),false);
            }
            validatePrecheck(identity,pre);
            if(request.retryOf()!=null) {
                var old=get(identity,request.retryOf());
                if(old.status()!=ConfigurationPackOperationWire.StatusValue.FAILED_ROLLED_BACK || !old.organizationId().equals(pre.response().organizationId())
                        || !old.facilityId().equals(pre.response().facilityId()) || !old.packCode().equals(pre.response().packCode()))
                    throw conflict("只能关联同一范围已确认回滚的原操作");
            }
            UUID id=UUID.randomUUID();
            jdbc.sql("""
                    insert into configuration_pack_operation(tenant_id,operation_id,organization_id,facility_id,precheck_id,
                      requested_by,idempotency_key,request_hash,pack_code,pack_version,manifest_hash,status,retry_of)
                    values (:tenant,:id,:org,:facility,:pre,:user,:key,:request,:code,:version,:hash,'RUNNING',:retry)
                    """).param("tenant",identity.tenantId()).param("id",id).param("org",pre.response().organizationId()).param("facility",pre.response().facilityId())
                    .param("pre",request.precheckId()).param("user",identity.userId()).param("key",key).param("request",requestHash)
                    .param("code",pre.response().packCode()).param("version",pre.response().packVersion()).param("hash",pre.response().manifestHash()).param("retry",request.retryOf()).update();
            evidence(identity,"CONFIG_PACK_INSTALL_STARTED",id,Map.of("pack_code",pre.response().packCode(),"pack_version",pre.response().packVersion()));
            return new Admission(get(identity,id),true);
        });
        if(!admitted.execute()) return admitted.operation();
        UUID id=admitted.operation().operationId();
        try {
            tx.executeWithoutResult(unused -> {
                lockTenant(identity); lockOperation(identity,id);
                if(get(identity,id).status()!=ConfigurationPackOperationWire.StatusValue.RUNNING) return;
                validatePrecheck(identity,pre);
                var manifest=codec.parse(pre.raw()); int imported=0; int retained=0;
                // Precheck and the tenant lock freeze the target set; read version chains once, not for every item.
                var currentByCode=new java.util.HashMap<String,DocumentTemplateService.DocumentTemplateWire>();
                for (var template:templates.list(identity)) currentByCode.merge(template.templateCode(),template,
                    (left,right)->left.versionNo()>right.versionNo()?left:right);
                for(var item:manifest.templates()) {
                    if(pre.keep().contains(item.code())) { retained++;continue; }
                    var current=currentByCode.get(item.code());
                    String itemKey="pack-"+id+"-"+imported;
                    var saved=current==null ? templates.create(identity,itemKey,new DocumentTemplateService.DocumentTemplateCreateRequest(null,item.code(),item.name(),item.documentType(),
                            pre.response().organizationId(),pre.response().facilityId(),null,codec.schema(item),item.requiredFields(),codec.display(item)))
                        : templates.createVersion(identity,itemKey,current.templateId(),new DocumentTemplateService.DocumentTemplateVersionCreateRequest(
                            current.templateRowVersion(),codec.schema(item),item.requiredFields(),codec.display(item)));
                    String content=codec.content(saved.sectionSchema(),saved.requiredFields(),saved.displayRules());
                    jdbc.sql("""
                            insert into configuration_pack_item_source(tenant_id,operation_id,item_code,template_id,template_version_id,original_content,original_hash)
                            values (:tenant,:operation,:code,:template,:version,cast(:content as jsonb),:hash)
                            """).param("tenant",identity.tenantId()).param("operation",id).param("code",item.code()).param("template",saved.templateId())
                            .param("version",saved.templateVersionId()).param("content",content).param("hash",hash(content)).update();
                    imported++;
                }
                jdbc.sql("update configuration_pack_operation set status='SUCCEEDED',imported_count=:count,retained_count=:keep,finished_at=clock_timestamp() where tenant_id=:tenant and operation_id=:id")
                        .param("count",imported).param("keep",retained).param("tenant",identity.tenantId()).param("id",id).update();
                evidence(identity,"CONFIG_PACK_INSTALLED",id,Map.of("imported_count",imported,"retained_count",retained,"pack_code",manifest.code(),"pack_version",manifest.version()));
            });
        } catch(RuntimeException failure) {
            // A separate transaction preserves failure evidence after the complete domain transaction rolled back.
            tx.executeWithoutResult(unused -> {
                lockTenant(identity);lockOperation(identity,id);
                if(get(identity,id).status()==ConfigurationPackOperationWire.StatusValue.RUNNING) {
                    jdbc.sql("update configuration_pack_operation set status='FAILED_ROLLED_BACK',error_code='CONFIG_PACK_INSTALL_FAILED',finished_at=clock_timestamp() where tenant_id=:tenant and operation_id=:id")
                            .param("tenant",identity.tenantId()).param("id",id).update();
                    evidence(identity,"CONFIG_PACK_INSTALL_FAILED",id,Map.of("error_code","CONFIG_PACK_INSTALL_FAILED"));
                }
            });
        }
        return get(identity,id);
    }

    List<ConfigurationPackOperationWire> list(ClinicalIdentity identity,UUID org,UUID facility) {
        authorize(identity,org,facility);
        return jdbc.sql("select * from configuration_pack_operation where tenant_id=:tenant and organization_id=:org and facility_id=:facility order by created_at desc,operation_id desc limit 500")
                .param("tenant",identity.tenantId()).param("org",org).param("facility",facility).query((rs,n)->operation(rs)).list();
    }
    ConfigurationPackOperationWire get(ClinicalIdentity identity,UUID id) {
        var value=jdbc.sql("select * from configuration_pack_operation where tenant_id=:tenant and operation_id=:id")
                .param("tenant",identity.tenantId()).param("id",id).query((rs,n)->operation(rs)).optional()
                .orElseThrow(()->new ConfigurationPackException("CONFIG_PACK_NOT_FOUND",404,"找不到当前机构的安装记录"));
        authorize(identity,value.organizationId(),value.facilityId());return value;
    }
    ConfigurationPackOperationWire reconcile(ClinicalIdentity identity,UUID id) {
        var before=get(identity,id);
        if(before.status()!=ConfigurationPackOperationWire.StatusValue.RUNNING && before.status()!=ConfigurationPackOperationWire.StatusValue.RECOVERY_REQUIRED) return before;
        if(before.createdAt().isAfter(Instant.now().minusSeconds(120))) return before;
        tx.executeWithoutResult(unused->{
            // Tenant lock is shared with execution. SKIP LOCKED never mistakes an active transaction for a crashed one.
            boolean available=jdbc.sql("select tenant_id from tenant where tenant_id=:tenant for update skip locked")
                    .param("tenant",identity.tenantId()).query(UUID.class).optional().isPresent();
            if(!available)return;
            lockOperation(identity,id);var current=get(identity,id);
            if(current.status()==ConfigurationPackOperationWire.StatusValue.RUNNING || current.status()==ConfigurationPackOperationWire.StatusValue.RECOVERY_REQUIRED) {
                jdbc.sql("update configuration_pack_operation set status='FAILED_ROLLED_BACK',error_code='CONFIG_PACK_INTERRUPTED',finished_at=clock_timestamp() where tenant_id=:tenant and operation_id=:id")
                        .param("tenant",identity.tenantId()).param("id",id).update();
                evidence(identity,"CONFIG_PACK_RECOVERED",id,Map.of("error_code","CONFIG_PACK_INTERRUPTED"));
            }
        });
        return get(identity,id);
    }

    private void validatePrecheck(ClinicalIdentity identity,Precheck pre) {
        authorize(identity,pre.response().organizationId(),pre.response().facilityId());
        if(pre.response().state()!=ConfigurationPackPrecheckWire.StateValue.READY || !pre.response().expiresAt().isAfter(Instant.now()) || !pre.snapshot().equals(snapshot(identity)))
            throw conflict("预检已阻断或失效，请重新预检");
        var manifest=codec.parse(pre.raw());
        if(!issues(identity,pre.response().organizationId(),pre.response().facilityId(),manifest,pre.raw(),
                differences(identity,pre.response().organizationId(),pre.response().facilityId(),manifest,pre.keep(),pre.response().differences().stream().filter(d -> d.action()==ConfigurationPackDifferenceWire.ActionValue.UPDATE).map(ConfigurationPackDifferenceWire::code).toList())).isEmpty())
            throw conflict("配置或信任条件已经变化，请重新预检");
    }
    private List<String> issues(ClinicalIdentity identity,UUID org,UUID facility,ConfigurationPackManifestWire manifest,String raw,List<ConfigurationPackDifferenceWire> diff) {
        var issues=new ArrayList<String>();
        long alreadyInstalled=jdbc.sql("select count(*) from configuration_pack_operation where tenant_id=:tenant and organization_id=:org and facility_id=:facility and pack_code=:code and pack_version=:version and status='SUCCEEDED'")
                .param("tenant",identity.tenantId()).param("org",org).param("facility",facility).param("code",manifest.code()).param("version",manifest.version()).query(Long.class).single();
        if(alreadyInstalled>0) issues.add("该版本已安装，请到配置安装记录查看原结果；升级须使用新的包版本");
        var installedVersions=jdbc.sql("select pack_version from configuration_pack_operation where tenant_id=:tenant and organization_id=:org and facility_id=:facility and pack_code=:code and status='SUCCEEDED'")
                .param("tenant",identity.tenantId()).param("org",org).param("facility",facility).param("code",manifest.code()).query(String.class).list();
        if(installedVersions.stream().anyMatch(version->compareVersions(version,manifest.version())>0))
            issues.add("不能通过安装入口降级，请到原模板版本流程处理");
        if(!"CONFIG".equals(manifest.type())) issues.add("演示数据不通过配置安装入口导入");
        if(!trusted(raw)) issues.add("发布来源尚未获部署方认可，不能安装");
        if(!appVersion.equals(manifest.appVersion())) issues.add("配置包不兼容当前应用版本");
        for(var dependency:manifest.dependencies()) {
            long found=jdbc.sql("select count(*) from configuration_pack_operation where tenant_id=:tenant and organization_id=:org and facility_id=:facility and pack_code=:code and pack_version=:version and status='SUCCEEDED'")
                    .param("tenant",identity.tenantId()).param("org",org).param("facility",facility).param("code",dependency.code()).param("version",dependency.version()).query(Long.class).single();
            if(found==0)issues.add("缺少依赖："+dependency.code()+" "+dependency.version());
        }
        if(diff.stream().anyMatch(x->x.action()==ConfigurationPackDifferenceWire.ActionValue.CONFLICT))issues.add("存在未处理的医院配置冲突");
        return issues;
    }
    private List<ConfigurationPackDifferenceWire> differences(ClinicalIdentity identity,UUID org,UUID facility,ConfigurationPackManifestWire manifest,List<String> keep,List<String> drafts) {
        var all=templates.list(identity);var result=new ArrayList<ConfigurationPackDifferenceWire>();
        for(var item:manifest.templates()) {
            String incoming=codec.content(codec.schema(item),item.requiredFields(),codec.display(item));
            var current=all.stream().filter(x->x.templateCode().equals(item.code())).max(java.util.Comparator.comparingInt(DocumentTemplateService.DocumentTemplateWire::versionNo)).orElse(null);
            String base="",local="",reason="新建模板草稿";var action=ConfigurationPackDifferenceWire.ActionValue.NEW;
            if(current!=null) {
                boolean scope=org.equals(current.organizationId())&&facility.equals(current.facilityId())&&current.departmentId()==null&&"ACTIVE".equals(current.lifecycleStatus())&&item.documentType().equals(current.documentTypeCode());
                if(!scope) { action=ConfigurationPackDifferenceWire.ActionValue.CONFLICT;reason="相同编码属于不同范围、文书类型或已停用模板，不能安装"; }
                else {
                    local=codec.content(current.sectionSchema(),current.requiredFields(),current.displayRules());
                    var source=jdbc.sql("""
                            select s.original_content::text,s.template_version_id from configuration_pack_item_source s
                            join clinical_document_template_version v on v.tenant_id=s.tenant_id and v.template_version_id=s.template_version_id
                            where s.tenant_id=:tenant and s.template_id=:template order by v.version_no desc limit 1
                            """).param("tenant",identity.tenantId()).param("template",current.templateId())
                            .query((rs,n)->new String[]{rs.getString(1),rs.getString(2)}).optional();
                    base=source.map(x->x[0]).orElse("");
                    boolean exactSource=source.map(x->x[1].equals(current.templateVersionId().toString())).orElse(false);
                    if(keep.contains(item.code())) {action=ConfigurationPackDifferenceWire.ActionValue.KEEP;reason="保留医院版本，不改变来源";}
                    else if(drafts.contains(item.code())) {action=ConfigurationPackDifferenceWire.ActionValue.UPDATE;reason="已明确选择候选新草稿，医院历史与发布版本保持不变";}
                    else if(!exactSource || !codec.same(base,local)) {action=ConfigurationPackDifferenceWire.ActionValue.CONFLICT;reason="医院修改或来源未知，默认保留；确认后重新预检";}
                    else {action=ConfigurationPackDifferenceWire.ActionValue.UPDATE;reason="建立新草稿，原已发布版本继续有效";}
                }
            } else if(keep.contains(item.code()) || drafts.contains(item.code())) { action=ConfigurationPackDifferenceWire.ActionValue.CONFLICT;reason="不存在的医院条目不能选择保留或历史升级"; }
            if(current==null && all.stream().anyMatch(x->"ACTIVE".equals(x.lifecycleStatus())&&org.equals(x.organizationId())&&facility.equals(x.facilityId())&&x.departmentId()==null&&item.documentType().equals(x.documentTypeCode()))) {
                action=ConfigurationPackDifferenceWire.ActionValue.CONFLICT;reason="相同范围已有另一编码的文书类型模板";
            }
            result.add(new ConfigurationPackDifferenceWire(item.code(),item.name(),action,reason,base,local,incoming));
        }
        return result;
    }
    private Precheck loadPrecheck(ClinicalIdentity identity,UUID id) {
        return jdbc.sql("select manifest_json,keep_codes::text,target_snapshot,response::text from configuration_pack_precheck where tenant_id=:tenant and precheck_id=:id and requested_by=:user")
                .param("tenant",identity.tenantId()).param("id",id).param("user",identity.userId())
                .query((rs,n)->new Precheck(rs.getString(1),Arrays.asList(mapper.readValue(rs.getString(2),String[].class)),rs.getString(3),mapper.readValue(rs.getString(4),ConfigurationPackPrecheckWire.class)))
                .optional().orElseThrow(()->new ConfigurationPackException("CONFIG_PACK_NOT_FOUND",404,"找不到当前管理员的预检"));
    }
    private String snapshot(ClinicalIdentity identity) { return hash(codec.json(templates.list(identity))); }
    private ConfigurationPackOperationWire operation(ResultSet rs) throws SQLException {
        UUID tenant=rs.getObject("tenant_id",UUID.class),id=rs.getObject("operation_id",UUID.class);
        var versions=jdbc.sql("select template_version_id from configuration_pack_item_source where tenant_id=:tenant and operation_id=:id order by item_code")
                .param("tenant",tenant).param("id",id).query(UUID.class).list();
        OffsetDateTime end=rs.getObject("finished_at",OffsetDateTime.class);
        return new ConfigurationPackOperationWire(id,rs.getObject("organization_id",UUID.class),rs.getObject("facility_id",UUID.class),rs.getString("pack_code"),rs.getString("pack_version"),rs.getString("manifest_hash"),
                ConfigurationPackOperationWire.StatusValue.valueOf(rs.getString("status")),rs.getObject("requested_by",UUID.class),rs.getObject("retry_of",UUID.class),rs.getObject("created_at",OffsetDateTime.class).toInstant(),end==null?null:end.toInstant(),rs.getString("error_code"),rs.getInt("imported_count"),rs.getInt("retained_count"),versions);
    }
    private void authorize(ClinicalIdentity identity,UUID org,UUID facility) {
        if(org==null||facility==null||identity.roleAssignmentIds().isEmpty())throw denied();
        long count=jdbc.sql("""
                select count(*) from role_assignment role join app_user account on account.tenant_id=role.tenant_id and account.user_id=role.user_id
                join workforce_person person on person.tenant_id=account.tenant_id and person.person_id=account.person_id
                join facility f on f.tenant_id=role.tenant_id and f.facility_id=:facility and f.organization_id=:org
                join organization o on o.tenant_id=f.tenant_id and o.organization_id=f.organization_id
                where role.tenant_id=:tenant and role.user_id=:user and role.role_assignment_id in (:roles)
                and role.organization_id=:org and (role.facility_id is null or role.facility_id=:facility)
                and role.role_code in ('SYSTEM_ADMIN','CLINICAL_ADMIN') and role.status='ACTIVE'
                and role.valid_from<=now() and (role.valid_until is null or role.valid_until>now())
                and account.status='ACTIVE' and person.status='ACTIVE' and person.effective_from<=now()
                and (person.effective_until is null or person.effective_until>now()) and f.status='ACTIVE' and o.status='ACTIVE'
                """).param("tenant",identity.tenantId()).param("user",identity.userId()).param("roles",identity.roleAssignmentIds()).param("org",org).param("facility",facility).query(Long.class).single();
        if(count==0)throw denied();
    }
    private boolean localFixtureEnabled(){return environment.matchesProfiles("dev-synthetic")&&!environment.matchesProfiles("prod");}
    private String localFixture(){try{return new ClassPathResource("configuration-packs/local-outpatient.json").getContentAsString(StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("Local fixture unavailable",e);}}
    private boolean trusted(String raw){return Arrays.stream(trustedHashes.split(",")).map(String::trim).anyMatch(x->x.equals(hash(raw)))||(localFixtureEnabled()&&hash(localFixture()).equals(hash(raw)));}
    private void lockTenant(ClinicalIdentity identity){jdbc.sql("select tenant_id from tenant where tenant_id=:tenant for update").param("tenant",identity.tenantId()).query(UUID.class).single();}
    private void lockOperation(ClinicalIdentity identity,UUID id){jdbc.sql("select operation_id from configuration_pack_operation where tenant_id=:tenant and operation_id=:id for update").param("tenant",identity.tenantId()).param("id",id).query(UUID.class).single();}
    private void evidence(ClinicalIdentity identity,String action,UUID resource,Map<String,Object> details){
        lockTenant(identity);
        String previous=jdbc.sql("select event_hash from audit_event where tenant_id=:tenant order by occurred_at desc,audit_event_id desc limit 1").param("tenant",identity.tenantId()).query(String.class).optional().orElse(null);
        UUID event=UUID.randomUUID();String trace=UUID.randomUUID().toString();
        jdbc.sql("""
                insert into audit_event(tenant_id,audit_event_id,occurred_at,actor_user_id,action_code,resource_type,resource_id,trace_id,previous_hash,event_hash,details)
                values (:tenant,:event,clock_timestamp(),:user,:action,'CONFIGURATION_PACK',:resource,:trace,:previous,:hash,cast(:details as jsonb))
                """).param("tenant",identity.tenantId()).param("event",event).param("user",identity.userId()).param("action",action).param("resource",resource).param("trace",trace).param("previous",previous)
                .param("hash",hash(identity.tenantId()+"|"+event+"|"+action+"|"+resource+"|"+trace+"|"+previous)).param("details",codec.json(details)).update();
        jdbc.sql("""
                insert into outbox_event(tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload)
                values (:tenant,:event,'CONFIGURATION_PACK',:resource,1,:action,1,cast(:details as jsonb))
                """).param("tenant",identity.tenantId()).param("event",UUID.randomUUID()).param("resource",resource).param("action",action).param("details",codec.json(details)).update();
    }
    private static int compareVersions(String left,String right) {
        String[] a=left.split("\\."),b=right.split("\\.");
        for(int n=0;n<3;n++){int comparison=Integer.compare(Integer.parseInt(a[n]),Integer.parseInt(b[n]));if(comparison!=0)return comparison;}
        return 0;
    }
    private static void validKey(String key){if(key==null||key.isBlank()||key.length()>128)throw invalid("需要有效的 Idempotency-Key");}
    private static ConfigurationPackException conflict(String message){return new ConfigurationPackException("CONFIG_PACK_CONFLICT",409,message);}
    private static ConfigurationPackException denied(){return new ConfigurationPackException("CONFIG_PACK_ACCESS_DENIED",403,"当前岗位不能管理此机构或院区的配置安装");}
    private record Precheck(String raw,List<String> keep,String snapshot,ConfigurationPackPrecheckWire response){}
    private record Admission(ConfigurationPackOperationWire operation,boolean execute){}
}
