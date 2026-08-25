package com.example.ossdoc.global.llm.service.support;

import com.example.ossdoc.global.llm.config.LlmGenerationProperties;
import com.example.ossdoc.global.llm.model.CoreMethodSeed;
import com.example.ossdoc.global.llm.model.CoreTypeSeed;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmInputAssemblerBuildSupportSemanticEdgeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmInputAssemblerBuildSupport support =
            new LlmInputAssemblerBuildSupport(objectMapper, new LlmGenerationProperties());

    @Test
    void extractCoreMethods_usesHttpEndpointAsMethodSeed() throws Exception {
        JsonNode apiMap = objectMapper.readTree("""
                {
                  "entry_points": [{
                    "symbol_id": "type-controller",
                    "owner_type_fqn": "org.acme.OrderController",
                    "simple_name": "OrderController",
                    "role": "PRIMARY",
                    "confidence": "HIGH",
                    "source_file": "src/main/java/org/acme/OrderController.java",
                    "entry_methods": [{
                      "symbol_id": "method-create",
                      "simple_name": "create",
                      "reason": "HTTP_ENDPOINT",
                      "http_endpoints": [{
                        "http_method": "POST",
                        "path": "/orders",
                        "confidence": 0.93,
                        "resolution": "RESOLVED",
                        "default_visible": true
                      }]
                    }]
                  }]
                }
                """);

        CoreTypeSeed controller = new CoreTypeSeed(
                "type-controller",
                "org.acme.OrderController",
                "org.acme",
                "OrderController",
                "src/main/java/org/acme/OrderController.java",
                "PRIMARY",
                "HTTP entry",
                20,
                10,
                40
        );

        List<CoreMethodSeed> methods = support.extractCoreMethods(
                apiMap,
                objectMapper.createObjectNode(),
                List.of(controller),
                objectMapper.createObjectNode(),
                Map.of("type-controller", "ENTRY_POINT")
        );

        assertThat(methods).hasSize(1);
        CoreMethodSeed method = methods.get(0);
        assertThat(method.methodName()).isEqualTo("create");
        assertThat(method.summarySeed()).contains("POST /orders");
        assertThat(method.scenarioHint()).contains("POST /orders");
        assertThat(method.importance()).isGreaterThanOrEqualTo(20);
    }

    @Test
    void buildEvidenceIndex_preservesSemanticRelationMetadataFromRuleCandidate() throws Exception {
        JsonNode ruleCandidates = objectMapper.readTree("""
                {
                  "candidates": [{
                    "evidences": [{
                      "evidenceId": 101,
                      "evidenceType": "AST",
                      "filePath": "src/main/java/org/acme/OrderService.java",
                      "startLine": 41,
                      "endLine": 41,
                      "snippet": "new Order(request)",
                      "edgeType": "CREATES",
                      "edgeOrigin": "AST",
                      "edgeDerivationKind": "EXTRACTED",
                      "edgeResolution": "RESOLVED",
                      "edgeResolutionReason": "TYPE_SOLVED",
                      "edgeConfidence": 0.92,
                      "edgeCallSiteLine": 41,
                      "edgeDefaultVisible": true,
                      "edgeAttrs": {"confidence_band": "HIGH"},
                      "signalConfidenceHint": 0.92,
                      "signalMeta": {"sourceDetail": "edge:CREATES"}
                    }]
                  }]
                }
                """);

        JsonNode evidenceIndex = support.buildEvidenceIndex(List.of(), List.of(), ruleCandidates);

        assertThat(evidenceIndex).hasSize(1);
        JsonNode evidence = evidenceIndex.get(0);
        assertThat(evidence.path("edgeType").asText()).isEqualTo("CREATES");
        assertThat(evidence.path("edgeResolution").asText()).isEqualTo("RESOLVED");
        assertThat(evidence.path("edgeConfidence").asDouble()).isEqualTo(0.92d);
        assertThat(evidence.path("edgeDefaultVisible").asBoolean()).isTrue();
        assertThat(evidence.path("edgeAttrs").path("confidence_band").asText()).isEqualTo("HIGH");
        assertThat(evidence.path("signalMeta").path("sourceDetail").asText()).isEqualTo("edge:CREATES");
    }
    @Test
    void buildExtensionSeed_usesSpiSemanticRelationAsReason() throws Exception {
        JsonNode apiMap = objectMapper.readTree("""
                {
                  "extension_points": [{
                    "symbol_id": "type-plugin",
                    "qualified_name": "org.acme.spi.Plugin",
                    "simple_name": "Plugin",
                    "confidence": "HIGH",
                    "signals": ["SPI_RELATION_RESOLVED"],
                    "semantic_relations": [{
                      "edge_type": "PROVIDES_SPI",
                      "source_symbol_id": "type-plugin-impl",
                      "confidence": 0.94,
                      "resolution": "RESOLVED",
                      "default_visible": true
                    }]
                  }]
                }
                """);

        JsonNode extensionSeed = support.buildExtensionSeed(
                apiMap,
                List.of(),
                objectMapper.createObjectNode()
        );

        assertThat(extensionSeed).hasSize(1);
        JsonNode seed = extensionSeed.get(0);
        assertThat(seed.path("reason").asText()).contains("SPI provider");
        assertThat(seed.path("confidenceSource").asText()).isEqualTo("의미그래프");
        assertThat(seed.path("semanticRelations").get(0).path("resolution").asText())
                .isEqualTo("RESOLVED");
    }

}
