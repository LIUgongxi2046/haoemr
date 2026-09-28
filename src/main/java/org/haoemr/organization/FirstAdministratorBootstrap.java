package org.haoemr.organization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** One-time roots needed before an authenticated organization/workforce command can exist. */
@Service
final class FirstAdministratorBootstrap {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    FirstAdministratorBootstrap(JdbcClient jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc=jdbc; this.transactions=transactions; this.mapper=mapper;
    }

    Result initialize(Request request, String configuredIssuer) {
        validate(request,configuredIssuer);
        String requestHash=hash(mapper.writeValueAsString(request));
        return transactions.execute(unused->{
            jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
                .param("scope","haoemr:first-administrator").query(Object.class).single();
            var existing=jdbc.sql("select tenant_id from tenant where tenant_id=:id or tenant_code=:code")
                .param("id",request.tenantId()).param("code",request.tenantCode()).query(UUID.class).list();
            if(!existing.isEmpty()) {
                // Immutable audit proves an exact replay; never recreate a disabled or changed administrator.
                var previous=jdbc.sql("select details::text from audit_event where tenant_id=:tenant and action_code='FIRST_ADMINISTRATOR_BOOTSTRAPPED' order by occurred_at limit 1")
                    .param("tenant",request.tenantId()).query(String.class).optional();
                if(previous.isEmpty()) throw new IllegalStateException("Existing tenant requires the original administration workflow");
                var evidence=mapper.readTree(previous.get());
                if(!requestHash.equals(evidence.path("request_hash").asString())) throw new IllegalStateException("Bootstrap replay must match the original request; it cannot change an administrator");
                return mapper.treeToValue(evidence.path("result"),Result.class);
            }
            UUID org=UUID.randomUUID(),facility=UUID.randomUUID(),user=UUID.randomUUID(),role=UUID.randomUUID();
            jdbc.sql("insert into tenant(tenant_id,tenant_code,display_name,status) values (:tenant,:code,:name,'ACTIVE')")
                .param("tenant",request.tenantId()).param("code",request.tenantCode()).param("name",request.organizationName()).update();
            jdbc.sql("insert into organization(tenant_id,organization_id,organization_code,display_name,status) values (:tenant,:org,:code,:name,'ACTIVE')")
                .param("tenant",request.tenantId()).param("org",org).param("code",request.organizationCode()).param("name",request.organizationName()).update();
            jdbc.sql("insert into facility(tenant_id,organization_id,facility_id,facility_code,display_name,status) values (:tenant,:org,:facility,:code,:name,'ACTIVE')")
                .param("tenant",request.tenantId()).param("org",org).param("facility",facility).param("code",request.facilityCode()).param("name",request.facilityName()).update();
            // Existing account/person and role/workforce triggers remain responsible for their linked records.
            jdbc.sql("insert into app_user(tenant_id,user_id,external_subject,display_name,status) values (:tenant,:user,:subject,:name,'ACTIVE')")
                .param("tenant",request.tenantId()).param("user",user).param("subject",request.externalSubject()).param("name",request.administratorName()).update();
            jdbc.sql("insert into role_assignment(tenant_id,role_assignment_id,user_id,organization_id,facility_id,role_code,valid_from,status) values (:tenant,:role,:user,:org,:facility,'SYSTEM_ADMIN',now(),'ACTIVE')")
                .param("tenant",request.tenantId()).param("role",role).param("user",user).param("org",org).param("facility",facility).update();
            Result result=new Result(request.tenantId(),org,facility,user,role);
            String details=mapper.writeValueAsString(Map.of("request_hash",requestHash,"issuer",request.issuer(),"result",result));
            UUID event=UUID.randomUUID();String trace=UUID.randomUUID().toString();
            jdbc.sql("""
                insert into audit_event(tenant_id,audit_event_id,occurred_at,actor_user_id,action_code,resource_type,resource_id,trace_id,previous_hash,event_hash,details)
                values (:tenant,:event,now(),:user,'FIRST_ADMINISTRATOR_BOOTSTRAPPED','ORGANIZATION_UNIT',:org,:trace,null,:hash,cast(:details as jsonb))
                """).param("tenant",request.tenantId()).param("event",event).param("user",user).param("org",org).param("trace",trace)
                .param("hash",hash(request.tenantId()+"|"+event+"|FIRST_ADMINISTRATOR_BOOTSTRAPPED|"+org+"|"+trace+"|null")).param("details",details).update();
            jdbc.sql("""
                insert into outbox_event(tenant_id,event_id,aggregate_type,aggregate_id,aggregate_version,event_type,schema_version,payload)
                values (:tenant,:event,'ORGANIZATION_UNIT',:org,1,'FIRST_ADMINISTRATOR_BOOTSTRAPPED',1,cast(:details as jsonb))
                """).param("tenant",request.tenantId()).param("event",UUID.randomUUID()).param("org",org).param("details",details).update();
            return result;
        });
    }
    private static void validate(Request r,String issuer) {
        if(r==null||r.tenantId()==null)throw new IllegalArgumentException("Tenant identity is required");
        for(String code:new String[]{r.tenantCode(),r.organizationCode(),r.facilityCode()})
            if(code==null||!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("Invalid bootstrap code");
        for(String name:new String[]{r.organizationName(),r.facilityName(),r.administratorName(),r.externalSubject()})
            if(name==null||name.isBlank()||name.length()>256||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid bootstrap identity field");
        if(issuer==null||!issuer.equals(r.issuer())||!issuer.startsWith("https://"))throw new IllegalArgumentException("Bootstrap issuer must match configured production identity");
    }
    private static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    record Request(UUID tenantId,String tenantCode,String organizationCode,String organizationName,String facilityCode,String facilityName,String administratorName,String externalSubject,String issuer){}
    record Result(UUID tenantId,UUID organizationId,UUID facilityId,UUID userId,UUID roleAssignmentId){}
}
