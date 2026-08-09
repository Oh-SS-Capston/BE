package com.example.ossdoc.domain.extraction.service.support.evidence;

import com.example.ossdoc.domain.extraction.dto.model.EvidenceFact;
import com.example.ossdoc.domain.extraction.enums.EvidenceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BytecodeAnnotationEvidenceFactoryTest {

    @Test
    @DisplayName("bytecode annotation descriptor·values·role을 Evidence로 기록한다")
    void createsAnnotationEvidence() {
        Map<String, Object> values =
                new LinkedHashMap<>();

        values.put("name", List.of("service", "alias"));
        values.put("primary", true);

        EvidenceFact evidence =
                BytecodeAnnotationEvidenceFactory.create(
                        "build/classes/java/main/sample/Config.class",
                        "sample-app",
                        "method:sample.Config#service()",
                        "Lsample/Bean;",
                        "sample.Bean",
                        true,
                        2,
                        "bean_provider",
                        values
                );

        assertNotNull(evidence.id());
        assertEquals(
                EvidenceType.BYTECODE,
                evidence.type()
        );
        assertEquals(
                "method:sample.Config#service()",
                evidence.symbol()
        );
        assertEquals(
                "annotation",
                evidence.attrs().get("granularity")
        );
        assertEquals(
                "bean_provider",
                evidence.attrs().get("role")
        );
        assertEquals(
                "sample.Bean",
                evidence.attrs().get("annotation_name")
        );
        assertEquals(
                "Lsample/Bean;",
                evidence.attrs().get("descriptor")
        );
        assertEquals(
                2,
                evidence.attrs().get("annotation_index")
        );
        assertEquals(
                true,
                evidence.attrs().get("runtime_visible")
        );
        assertEquals(
                "@sample.Bean(name=[\"service\", \"alias\"], primary=true)",
                evidence.snippet()
        );
        assertNull(evidence.startLine());
        assertNull(evidence.startCol());
        assertNotNull(evidence.hash());
    }

    @Test
    @DisplayName("같은 annotation도 relation과 semantic role별 ID가 분리된다")
    void roleSeparatesEvidenceIdentity() {
        EvidenceFact relation =
                create("annotation");

        EvidenceFact semantic =
                create("event_subscription");

        assertNotEquals(
                relation.id(),
                semantic.id()
        );
        assertEquals(
                relation.attrs().get("annotation_index"),
                semantic.attrs().get("annotation_index")
        );
    }

    @Test
    @DisplayName("같은 입력은 안정적인 Evidence ID를 만든다")
    void createsStableId() {
        assertEquals(
                create("annotation").id(),
                create("annotation").id()
        );
    }

    private EvidenceFact create(String role) {
        return BytecodeAnnotationEvidenceFactory.create(
                "build/classes/java/main/sample/Listener.class",
                "sample-app",
                "method:sample.Listener#handle(sample.Event)",
                "Lsample/EventListener;",
                "sample.EventListener",
                true,
                0,
                role,
                Map.of()
        );
    }
}
