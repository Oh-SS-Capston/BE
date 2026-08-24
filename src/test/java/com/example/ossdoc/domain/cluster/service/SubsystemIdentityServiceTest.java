package com.example.ossdoc.domain.cluster.service;

import com.example.ossdoc.domain.cluster.model.subsystem.Subsystem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubsystemIdentityServiceTest {

    private final SubsystemIdentityService service =
            new SubsystemIdentityService();

    @Test
    @DisplayName("동일 commit과 동일 멤버 집합은 순서가 달라도 동일 ID를 생성한다")
    void stableId_shouldIgnoreMemberOrder() {
        // 같은 member이지만 입력 순서를 다르게 전달한다.
        String first = service.stableId(
                "abc123",
                List.of("B", "A", "C")
        );

        String second = service.stableId(
                "abc123",
                List.of("C", "B", "A")
        );

        // member 순서와 관계없이 동일한 subsystem ID가 나와야 한다.
        assertThat(first)
                .isEqualTo(second)
                .startsWith("ss_");
    }

    @Test
    @DisplayName("멤버 구성이 달라지면 subsystem ID도 달라진다")
    void stableId_shouldChangeWhenMembersChange() {
        String first = service.stableId(
                "abc123",
                List.of("A", "B")
        );

        String second = service.stableId(
                "abc123",
                List.of("A", "C")
        );

        assertThat(first)
                .isNotEqualTo(second);
    }

    @Test
    @DisplayName("rekey는 기존 메타데이터를 유지하면서 ID만 변경한다")
    void rekey_shouldPreserveSubsystemMetadata() {
        Subsystem input = Subsystem.builder()
                .subsystemId("ss_001")
                .name("auth")
                .score(0.0)
                .memberSymbolIds(List.of("A", "B"))
                .entrySymbolIds(List.of("A"))
                .coreSymbolIds(List.of())
                .packageRoots(List.of("com.example.auth"))
                .build();

        Subsystem output = service.rekey(
                List.of(input),
                "abc123"
        ).get(0);

        // 기존 순번 ID가 deterministic ID로 변경되어야 한다.
        assertThat(output.getSubsystemId())
                .startsWith("ss_")
                .isNotEqualTo("ss_001");

        // subsystem의 실제 정보는 유지되어야 한다.
        assertThat(output.getName())
                .isEqualTo("auth");

        assertThat(output.getMemberSymbolIds())
                .containsExactly("A", "B");
    }
}