// 역할: run 기준 Symbol 정보를 바탕으로 public_api_entry를 일관되게 재생성한다.
package com.example.ossdoc.domain.publicapi.service;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntry;
import com.example.ossdoc.domain.publicapi.entity.PublicApiEntryId;
import com.example.ossdoc.domain.publicapi.repository.PublicApiEntryRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class PublicApiEntrySyncService {

    private final PublicApiEntryRepository publicApiEntryRepository;
    private final SymbolRepository symbolRepository;

    /**
     * run의 TYPE 심볼을 읽어 공개 API 엔트리를 동기화하고, 공개 API symbol id 집합을 반환한다.
     */
    public Set<String> ensureTypeEntries(RepoRun run) {
        List<SymbolEntity> typeSymbols = symbolRepository.findAllByRun_RunIdAndSymbolKind(run.getRunId(), SymbolKind.TYPE);
        return ensureTypeEntries(run, typeSymbols);
    }

    /**
     * 전달받은 TYPE 심볼 목록을 기준으로 공개 API 엔트리를 전량 재생성한다.
     */
    public Set<String> ensureTypeEntries(RepoRun run, List<SymbolEntity> typeSymbols) {
        List<SymbolEntity> visibleTypes = collectVisibleTypes(typeSymbols);

        publicApiEntryRepository.deleteByRun_RunId(run.getRunId());
        if (visibleTypes.isEmpty()) {
            return Set.of();
        }

        List<PublicApiEntry> entries = new ArrayList<>(visibleTypes.size());
        LinkedHashSet<String> symbolIds = new LinkedHashSet<>();

        for (SymbolEntity symbol : visibleTypes) {
            PublicApiEntry entry = new PublicApiEntry(
                    new PublicApiEntryId(run.getRunId(), symbol.getSymbolId()),
                    run,
                    symbol,
                    SymbolKind.TYPE,
                    resolveExposure(symbol.getAccess()),
                    buildReason(symbol)
            );
            entries.add(entry);
            symbolIds.add(symbol.getSymbolId());
        }

        publicApiEntryRepository.saveAll(entries);
        return symbolIds;
    }

    /**
     * TYPE 심볼 중 공개 표면으로 볼 public/protected 타입만 선별한다.
     */
    private List<SymbolEntity> collectVisibleTypes(List<SymbolEntity> typeSymbols) {
        if (typeSymbols == null || typeSymbols.isEmpty()) {
            return List.of();
        }

        List<SymbolEntity> visible = new ArrayList<>();
        for (SymbolEntity type : typeSymbols) {
            if (type == null || type.getSymbolKind() != SymbolKind.TYPE) {
                continue;
            }
            if (isPublicOrProtected(type.getAccess())) {
                visible.add(type);
            }
        }
        return List.copyOf(visible);
    }

    /**
     * access 값을 public_api_entry.exposure 문자열로 변환한다.
     */
    private String resolveExposure(AccessLevel accessLevel) {
        if (accessLevel == null) {
            return "UNKNOWN";
        }
        return accessLevel.name();
    }

    /**
     * 공개 API로 분류한 근거를 JSON으로 기록한다.
     */
    private ObjectNode buildReason(SymbolEntity symbol) {
        ObjectNode reason = JsonNodeFactory.instance.objectNode();
        reason.put("source", "graphstore_symbol_access");
        reason.put("symbolKind", symbol.getSymbolKind() == null ? "UNKNOWN" : symbol.getSymbolKind().name());
        reason.put("access", symbol.getAccess() == null ? "UNKNOWN" : symbol.getAccess().name());
        reason.put("rule", "TYPE and access in [PUBLIC, PROTECTED]");
        return reason;
    }

    /**
     * 공개 API 후보 접근 제어자(public/protected) 여부를 판별한다.
     */
    private boolean isPublicOrProtected(AccessLevel accessLevel) {
        return accessLevel == AccessLevel.PUBLIC || accessLevel == AccessLevel.PROTECTED;
    }
}
