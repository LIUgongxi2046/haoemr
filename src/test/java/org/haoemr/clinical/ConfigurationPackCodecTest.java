package org.haoemr.clinical;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

final class ConfigurationPackCodecTest {
    private final ConfigurationPackCodec codec = new ConfigurationPackCodec(new ObjectMapper());
    private String sample() throws Exception { return new ClassPathResource("configuration-packs/local-outpatient.json").getContentAsString(StandardCharsets.UTF_8); }
    @Test void acceptsOnlyClosedConfigurationAndProducesDraftSchema() throws Exception {
        var pack = codec.parse(sample());
        assertThat(pack.templates()).hasSize(3);
        assertThat(codec.schema(pack.templates().getFirst())).containsEntry("additionalProperties", false);
    }
    @Test void refusesExecutableExtraFieldsAndArchives() throws Exception {
        assertThatThrownBy(() -> codec.parse(sample().replace("\"schema_version\": 1", "\"sql\": \"DROP TABLE patient\", \"schema_version\": 1"))).isInstanceOf(ConfigurationPackException.class);
        assertThatThrownBy(() -> codec.parse("PK\u0003\u0004zip")).isInstanceOf(ConfigurationPackException.class);
    }
    @Test void rejectsDuplicatedTypesUnknownFieldsAndUndefinedRequiredFields() throws Exception {
        assertThatThrownBy(() -> codec.parse(sample().replace("LOCAL_OPD_REVIEW", "LOCAL_OPD_INITIAL"))).isInstanceOf(ConfigurationPackException.class);
        assertThatThrownBy(() -> codec.parse(sample().replace("\"required_fields\":[\"chief_complaint\"]", "\"required_fields\":[\"missing\"]"))).isInstanceOf(ConfigurationPackException.class);
        assertThatThrownBy(() -> codec.parse(sample().replace("1.0.0", "1.*"))).isInstanceOf(ConfigurationPackException.class);
    }
    @Test void acceptsExactlyTenMegabytesAndRejectsOneMoreByte() throws Exception {
        String raw=sample();String exact=raw+" ".repeat(ConfigurationPackCodec.MAX_BYTES-raw.getBytes(StandardCharsets.UTF_8).length);
        assertThat(codec.parse(exact).templates()).hasSize(3);
        assertThatThrownBy(()->codec.parse(exact+" ")).hasMessageContaining("10 MB");
    }
    @Test void rejectsOversizedInputsAndComparesContentByStructure() {
        assertThatThrownBy(() -> codec.parse("x".repeat(ConfigurationPackCodec.MAX_BYTES + 1))).isInstanceOf(ConfigurationPackException.class);
        assertThat(codec.same("{\"b\":2,\"a\":1}", "{\"a\":1,\"b\":2}")).isTrue();
    }
}
