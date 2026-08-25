package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicApiEntrySyncServiceTest {

    @Mock
    private SymbolRepository symbolRepository;

    @InjectMocks
    private PublicApiEntrySyncService publicApiEntrySyncService;

    @Test
    @DisplayName("public/protected TYPE 심볼만 공개 API symbolId로 반환한다")
    void ensureTypeEntries_shouldReturnVisibleTypes() {
        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");

        /*
         * 공개 TYPE.
         * PublicSymbolFilter 조건을 통과해야 한다.
         */
        SymbolEntity publicType = mock(SymbolEntity.class);
        when(publicType.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(publicType.getAccess()).thenReturn(AccessLevel.PUBLIC);
        when(publicType.getSymbolId()).thenReturn("type-public");

        /*
         * protected TYPE도 공개 API 대상으로 인정한다.
         */
        SymbolEntity protectedType = mock(SymbolEntity.class);
        when(protectedType.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(protectedType.getAccess()).thenReturn(AccessLevel.PROTECTED);
        when(protectedType.getSymbolId()).thenReturn("type-protected");

        /*
         * private TYPE은 공개 API 대상에서 제외되어야 한다.
         */
        SymbolEntity privateType = mock(SymbolEntity.class);
        when(privateType.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(privateType.getAccess()).thenReturn(AccessLevel.PRIVATE);

        /*
         * 현재 PublicApiEntrySyncService는 DB에 public_api_entry를 저장하지 않는다.
         *
         * run의 TYPE Symbol을 조회한 뒤 PublicSymbolFilter를 적용하고
         * 공개 TYPE의 symbolId 집합만 반환한다.
         */
        when(
                symbolRepository.findAllByRun_RunIdAndSymbolKind(
                        "run-1",
                        SymbolKind.TYPE
                )
        ).thenReturn(
                List.of(
                        publicType,
                        protectedType,
                        privateType
                )
        );

        Set<String> result =
                publicApiEntrySyncService.ensureTypeEntries(run);

        /*
         * Set은 순서를 보장하지 않으므로
         * containsExactlyInAnyOrder를 사용한다.
         */
        assertThat(result)
                .containsExactlyInAnyOrder(
                        "type-public",
                        "type-protected"
                );

        verify(symbolRepository)
                .findAllByRun_RunIdAndSymbolKind(
                        "run-1",
                        SymbolKind.TYPE
                );
    }

    @Test
    @DisplayName("가시 공개 TYPE이 없으면 빈 Set을 반환한다")
    void ensureTypeEntries_shouldReturnEmptySetWhenNoVisibleType() {
        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-2");

        /*
         * private TYPE만 존재하는 상황.
         */
        SymbolEntity privateType = mock(SymbolEntity.class);
        when(privateType.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(privateType.getAccess()).thenReturn(AccessLevel.PRIVATE);

        when(
                symbolRepository.findAllByRun_RunIdAndSymbolKind(
                        "run-2",
                        SymbolKind.TYPE
                )
        ).thenReturn(
                List.of(privateType)
        );

        Set<String> result =
                publicApiEntrySyncService.ensureTypeEntries(run);

        assertThat(result)
                .isEmpty();

        verify(symbolRepository)
                .findAllByRun_RunIdAndSymbolKind(
                        "run-2",
                        SymbolKind.TYPE
                );
    }
}