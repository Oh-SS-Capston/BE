package com.example.ossdoc.domain.publicapi.support;

import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntryPointJsonCodecTest {

    private final EntryPointJsonCodec codec = new EntryPointJsonCodec(new ObjectMapper());

    @Test
    @DisplayName("HANDLES_ENDPOINT 메타데이터를 ENTRY_POINTS_JSON 1.1 왕복 과정에서 보존한다")
    void shouldRoundTripHttpEndpointMetadata() {
        EntryPointCandidate.HttpEndpointInfo endpoint = EntryPointCandidate.HttpEndpointInfo.builder()
                .httpMethod("POST")
                .path("/runs")
                .confidence(0.93d)
                .resolution("RESOLVED")
                .resolutionReason("mapping resolved")
                .origin("OBSERVED")
                .derivationKind("DERIVED")
                .defaultVisible(true)
                .build();
        EntryPointCandidate candidate = EntryPointCandidate.builder()
                .symbolId("type:org.acme.RunController")
                .qualifiedName("org.acme.RunController")
                .simpleName("RunController")
                .typeKind("class")
                .confidence("HIGH")
                .role("PRIMARY")
                .score(10)
                .signals(List.of("HANDLES_ENDPOINT_RESOLVED"))
                .entryMethods(List.of(EntryPointCandidate.EntryMethodInfo.builder()
                        .symbolId("method:org.acme.RunController#create")
                        .simpleName("create")
                        .reason("HTTP_ENDPOINT")
                        .httpEndpoints(List.of(endpoint))
                        .build()))
                .build();

        var json = codec.serialize(List.of(candidate), "run-1");
        List<EntryPointCandidate> decoded = codec.deserialize(json);

        assertThat(json.path("schema_version").asText()).isEqualTo("1.1");
        assertThat(decoded).hasSize(1);
        var decodedMethod = decoded.get(0).getEntryMethods().get(0);
        assertThat(decodedMethod.getReason()).isEqualTo("HTTP_ENDPOINT");
        assertThat(decodedMethod.getHttpEndpoints()).hasSize(1);
        var decodedEndpoint = decodedMethod.getHttpEndpoints().get(0);
        assertThat(decodedEndpoint.getHttpMethod()).isEqualTo("POST");
        assertThat(decodedEndpoint.getPath()).isEqualTo("/runs");
        assertThat(decodedEndpoint.getConfidence()).isEqualTo(0.93d);
        assertThat(decodedEndpoint.getResolution()).isEqualTo("RESOLVED");
        assertThat(decodedEndpoint.getDefaultVisible()).isTrue();
    }
}
