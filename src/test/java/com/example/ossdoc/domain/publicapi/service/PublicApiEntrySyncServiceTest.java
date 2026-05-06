package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntry;
import com.example.ossdoc.domain.publicapi.repository.PublicApiEntryRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicApiEntrySyncServiceTest {

    @Mock
    private PublicApiEntryRepository publicApiEntryRepository;

    @Mock
    private SymbolRepository symbolRepository;

    @InjectMocks
    private PublicApiEntrySyncService publicApiEntrySyncService;

    @Test
    @DisplayName("public/protected TYPE 심볼만 public_api_entry로 재생성한다")
    void ensureTypeEntries_shouldRebuildEntriesWithVisibleTypes() {
        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-1");

        SymbolEntity publicType = mock(SymbolEntity.class);
        when(publicType.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(publicType.getAccess()).thenReturn(AccessLevel.PUBLIC);
        when(publicType.getSymbolId()).thenReturn("type-public");

        SymbolEntity protectedType = mock(SymbolEntity.class);
        when(protectedType.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(protectedType.getAccess()).thenReturn(AccessLevel.PROTECTED);
        when(protectedType.getSymbolId()).thenReturn("type-protected");

        SymbolEntity privateType = mock(SymbolEntity.class);
        when(privateType.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(privateType.getAccess()).thenReturn(AccessLevel.PRIVATE);

        SymbolEntity methodSymbol = mock(SymbolEntity.class);
        when(methodSymbol.getSymbolKind()).thenReturn(SymbolKind.METHOD);
        when(methodSymbol.getAccess()).thenReturn(AccessLevel.PUBLIC);

        when(symbolRepository.findAllByRun_RunIdAndSymbolKind("run-1", SymbolKind.TYPE))
                .thenReturn(List.of(publicType, protectedType, privateType, methodSymbol));

        Set<String> result = publicApiEntrySyncService.ensureTypeEntries(run);

        assertThat(result).containsExactly("type-public", "type-protected");
        verify(publicApiEntryRepository).deleteByRun_RunId("run-1");
        verify(publicApiEntryRepository).saveAll(argThat((List<PublicApiEntry> entries) -> entries.size() == 2));
    }

    @Test
    @DisplayName("가시 공개 타입이 없으면 엔트리를 비우고 저장은 생략한다")
    void ensureTypeEntries_shouldSkipSaveWhenNoVisibleType() {
        RepoRun run = mock(RepoRun.class);
        when(run.getRunId()).thenReturn("run-2");

        SymbolEntity privateType = mock(SymbolEntity.class);
        when(privateType.getSymbolKind()).thenReturn(SymbolKind.TYPE);
        when(privateType.getAccess()).thenReturn(AccessLevel.PRIVATE);

        when(symbolRepository.findAllByRun_RunIdAndSymbolKind("run-2", SymbolKind.TYPE))
                .thenReturn(List.of(privateType));

        Set<String> result = publicApiEntrySyncService.ensureTypeEntries(run);

        assertThat(result).isEmpty();
        verify(publicApiEntryRepository).deleteByRun_RunId("run-2");
        verify(publicApiEntryRepository, never()).saveAll(anyList());
    }
}
