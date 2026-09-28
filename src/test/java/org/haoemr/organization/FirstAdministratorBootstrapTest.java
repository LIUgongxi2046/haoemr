package org.haoemr.organization;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

final class FirstAdministratorBootstrapTest {
    private JdbcClient jdbc;
    private FirstAdministratorBootstrap bootstrap;
    private final String issuer="https://identity.example.test";
    @BeforeEach void setup() {
        String url=System.getenv("HAOEMR_INSTALL_DB_URL");assumeTrue(url!=null);
        assertThat(url).matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/haoemr_iteration_[a-z_]+$");
        var source=new DriverManagerDataSource(url,System.getenv().getOrDefault("HAOEMR_DB_USER","liuhaoxian"),"");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=JdbcClient.create(source);bootstrap=new FirstAdministratorBootstrap(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),new ObjectMapper());
    }
    private FirstAdministratorBootstrap.Request request() {
        UUID id=UUID.randomUUID();return new FirstAdministratorBootstrap.Request(id,"BOOT-"+id,"ORG","本地引导验收机构","MAIN","主院区","初始管理员","synthetic-"+id,issuer);
    }
    @Test void concurrentAndRepeatedBootstrapProducesOneBindingAndNeverResetsIt() {
        var request=request();
        var a=CompletableFuture.supplyAsync(()->bootstrap.initialize(request,issuer));
        var b=CompletableFuture.supplyAsync(()->bootstrap.initialize(request,issuer));
        var result=a.join();assertThat(b.join()).isEqualTo(result);
        for(String table:new String[]{"organization","facility","app_user","role_assignment","workforce_person","workforce_assignment","audit_event","outbox_event"})
            assertThat(count(table,request.tenantId())).as(table).isEqualTo(1);
        for(String table:new String[]{"patient","encounter","clinical_document","clinical_document_template"})assertThat(count(table,request.tenantId())).as(table).isZero();
        jdbc.sql("update app_user set status='DISABLED' where tenant_id=:tenant and user_id=:user").param("tenant",result.tenantId()).param("user",result.userId()).update();
        assertThat(bootstrap.initialize(request,issuer)).isEqualTo(result);
        assertThat(jdbc.sql("select status from app_user where tenant_id=:tenant and user_id=:user").param("tenant",result.tenantId()).param("user",result.userId()).query(String.class).single()).isEqualTo("DISABLED");
        var changed=new FirstAdministratorBootstrap.Request(request.tenantId(),request.tenantCode(),request.organizationCode(),"另一个名字",request.facilityCode(),request.facilityName(),request.administratorName(),request.externalSubject(),issuer);
        assertThatThrownBy(()->bootstrap.initialize(changed,issuer)).hasMessageContaining("cannot change");
    }
    @Test void rejectsExistingUnrelatedTenantAndIssuerMismatchWithoutBinding() {
        var request=request();
        assertThatThrownBy(()->bootstrap.initialize(request,"https://other.example.test")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("app_user",request.tenantId())).isZero();
        jdbc.sql("insert into tenant(tenant_id,tenant_code,display_name,status) values (:tenant,:code,'既有医院','ACTIVE')")
            .param("tenant",request.tenantId()).param("code",request.tenantCode()).update();
        assertThatThrownBy(()->bootstrap.initialize(request,issuer)).hasMessageContaining("original administration");
        assertThat(count("app_user",request.tenantId())).isZero();
    }
    private long count(String table,UUID tenant){return jdbc.sql("select count(*) from "+table+" where tenant_id=:tenant").param("tenant",tenant).query(Long.class).single();}
}
