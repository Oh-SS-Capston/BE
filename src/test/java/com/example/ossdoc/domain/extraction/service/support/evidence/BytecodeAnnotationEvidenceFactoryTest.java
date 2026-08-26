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

    /**
     * PostgreSQL의 text/jsonb는 U+0000을 저장할 수 없다(SQLState 22P05).
     * bytecode 어노테이션 값에는 실제로 NUL이 들어오므로(@kotlin.Metadata 등)
     * snippet과 attrs.values 양쪽에서 제거돼야 artifact 저장이 실패하지 않는다.
     */
    @Test
    @DisplayName("어노테이션 값의 NUL 문자를 snippet과 attrs 양쪽에서 제거한다")
    void stripsNulCharactersFromSnippetAndAttrs() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("d1", List.of("\0head", "tail\0"));
        values.put("d2", "plain\0value");

        EvidenceFact evidence =
                BytecodeAnnotationEvidenceFactory.create(
                        "build/classes/kotlin/main/sample/SampleKt.class",
                        "sample-app",
                        "type:sample.SampleKt",
                        "Lkotlin/Metadata;",
                        "kotlin.Metadata",
                        true,
                        0,
                        "annotation",
                        values
                );

        // NUL만 제거하고 나머지 문자는 보존해야 한다.
        assertEquals(
                "@kotlin.Metadata(d1=[\"head\", \"tail\"], d2=\"plainvalue\")",
                evidence.snippet()
        );
        assertEquals(
                -1,
                evidence.snippet().indexOf('\0'),
                "snippet에 NUL이 남아 있으면 안 된다"
        );
        assertEquals(
                -1,
                String.valueOf(evidence.attrs()).indexOf('\0'),
                "attrs에 NUL이 남아 있으면 안 된다"
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
