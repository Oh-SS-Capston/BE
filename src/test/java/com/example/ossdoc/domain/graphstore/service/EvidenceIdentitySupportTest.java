package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.entity.Evidence;
import com.example.ossdoc.domain.graphstore.enums.EvidenceType;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class EvidenceIdentitySupportTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Test
    @DisplayName("동일 snippet·hash라도 rawId와 role이 다르면 별도 Evidence로 유지한다")
    void rawIdPreventsRoleCollision() {
        Evidence annotation = evidence(
                "ast-annotation-id",
                "annotation"
        );

        Evidence beanProvider = evidence(
                "ast-bean-provider-id",
                "bean_provider"
        );

        Map<String, Evidence> rawIdLookup =
                new HashMap<>();

        Map<EvidenceIdentitySupport.Signature, Evidence>
                signatureLookup = new HashMap<>();

        EvidenceIdentitySupport.register(
                rawIdLookup,
                signatureLookup,
                annotation
        );

        assertSame(
                annotation,
                EvidenceIdentitySupport.findExisting(
                        rawIdLookup,
                        signatureLookup,
                        annotation
                )
        );

        assertNull(
                EvidenceIdentitySupport.findExisting(
                        rawIdLookup,
                        signatureLookup,
                        beanProvider
                ),
                "snippet/hash가 같아도 다른 rawId는 기존 Evidence와 합치면 안 됨"
        );

        EvidenceIdentitySupport.register(
                rawIdLookup,
                signatureLookup,
                beanProvider
        );

        assertEquals(2, rawIdLookup.size());
        assertSame(
                beanProvider,
                rawIdLookup.get(
                        "ast-bean-provider-id"
                )
        );
    }

    @Test
    @DisplayName("rawId가 없는 구버전 Evidence는 전체 메타데이터 signature로 중복 판정한다")
    void legacyEvidenceUsesCompleteSignature() {
        Evidence first = legacyEvidence(
                "method_call"
        );

        Evidence same = legacyEvidence(
                "method_call"
        );

        Evidence differentRole = legacyEvidence(
                "event_publication"
        );

        Map<String, Evidence> rawIdLookup =
                new HashMap<>();

        Map<EvidenceIdentitySupport.Signature, Evidence>
                signatureLookup = new HashMap<>();

        EvidenceIdentitySupport.register(
                rawIdLookup,
                signatureLookup,
                first
        );

        assertSame(
                first,
                EvidenceIdentitySupport.findExisting(
                        rawIdLookup,
                        signatureLookup,
                        same
                )
        );

        assertNull(
                EvidenceIdentitySupport.findExisting(
                        rawIdLookup,
                        signatureLookup,
                        differentRole
                )
        );
    }

    private Evidence evidence(
            String rawId,
            String role
    ) {
        return new Evidence(
                null,
                mock(RepoRun.class),
                EvidenceType.AST,
                null,
                5,
                5,
                5,
                9,
                "method:sample.Config#service()",
                "@Bean",
                "same-hash",
                rawId,
                objectMapper.valueToTree(Map.of(
                        "granularity", "annotation",
                        "role", role
                ))
        );
    }

    private Evidence legacyEvidence(
            String role
    ) {
        return new Evidence(
                null,
                mock(RepoRun.class),
                EvidenceType.AST,
                null,
                10,
                9,
                10,
                22,
                "method:sample.Sample#run()",
                "service.call()",
                "same-hash",
                null,
                objectMapper.valueToTree(Map.of(
                        "granularity", "expression",
                        "role", role
                ))
        );
    }
}
