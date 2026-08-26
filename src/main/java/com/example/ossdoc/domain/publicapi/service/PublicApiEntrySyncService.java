package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.support.PublicSymbolFilter;
import com.example.ossdoc.domain.run.entity.RepoRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * 역할:
 * run 기준 Symbol 정보를 바탕으로 공개 API TYPE의 symbolId 집합을 반환합니다.
 *
 * 공개 API 판정 기준은 PublicSymbolFilter에서 단일 관리합니다.
 * 필터 정책 변경 시 PublicSymbolFilter만 수정하면 됩니다.
 *
 * B-2: public_api_entry DB INSERT는 중단됨. public_api_entry 테이블과
 * PublicApiEntryRepository 파일은 유지하되 신규 적재는 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicApiEntrySyncService {

    private final SymbolRepository symbolRepository;

    /**
     * 역할:
     * run의 TYPE 심볼을 읽어 공개 API TYPE의 symbolId 집합을 반환합니다.
     */
    public Set<String> ensureTypeEntries(RepoRun run) {
        List<SymbolEntity> typeSymbols =
                symbolRepository.findAllByRun_RunIdAndSymbolKind(
                        run.getRunId(),
                        SymbolKind.TYPE
                );

        return ensureTypeEntries(run, typeSymbols);
    }

    /**
     * 역할:
     * 전달받은 TYPE 목록에서 공개 API TYPE의 symbolId 집합을 반환합니다.
     */
    public Set<String> ensureTypeEntries(RepoRun run, List<SymbolEntity> typeSymbols) {
        return collectVisibleTypes(typeSymbols).stream()
                .map(SymbolEntity::getSymbolId)
                .collect(Collectors.toSet());
    }

    // 판정 기준은 PublicSymbolFilter에서 단일 관리. 정책 변경 시 필터만 수정할 것.
    private List<SymbolEntity> collectVisibleTypes(List<SymbolEntity> typeSymbols) {
        if (typeSymbols == null || typeSymbols.isEmpty()) {
            return List.of();
        }
        return typeSymbols.stream()
                .filter(PublicSymbolFilter::isPublicApiType)
                .toList();
    }
}
