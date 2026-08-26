package com.example.ossdoc.domain.graphstore.service;

import com.example.ossdoc.domain.graphstore.entity.SymbolEntity;
import com.example.ossdoc.domain.graphstore.enums.AccessLevel;
import com.example.ossdoc.domain.graphstore.enums.OriginKind;
import com.example.ossdoc.domain.graphstore.enums.SymbolKind;
import com.example.ossdoc.domain.graphstore.repository.SymbolRepository;
import com.example.ossdoc.domain.run.entity.RepoRun;
import com.example.ossdoc.domain.run.enums.RunStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * GraphStoreIngestService가 symbol을 배치 저장한 뒤 owner/source span을 연결하는 순서를
 * 실제 영속성 컨텍스트에서 재현한다.
 *
 * <p>symbol의 @Id는 애플리케이션이 부여하는 String이라 Spring Data가 save()를 merge()로 처리한다.
 * merge()는 관리 사본을 반환하므로, 저장에 넘긴 원본 인스턴스를 그대로 들고 변경하면
 * 그 변경이 DB에 반영되지 않는다. 검증은 flush + clear 후 재조회로만 가능하다.
 * (1차 캐시가 살아있는 상태에서 재조회하면 변경된 in-memory 객체가 그대로 돌아와 통과해버린다.)</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SymbolBatchSavePersistenceTest {

    private static final String RUN_ID = "run_symbol_persistence_test";

    @Autowired
    private SymbolRepository symbolRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("배치 저장 후 연결한 owner/source span이 DB에 반영된다")
    void persistsOwnerAndSourceSpanAssignedAfterBatchSave() {
        RepoRun run = persistRun();

        SymbolEntity ownerType = newSymbol(run, "com.example.Foo", SymbolKind.TYPE);
        SymbolEntity method = newSymbol(run, "com.example.Foo#bar()", SymbolKind.METHOD);

        // 운영 코드와 동일하게 반환값을 쓰지 않고 배치 저장한다.
        symbolRepository.saveAll(List.of(ownerType, method));

        // GraphStoreIngestService가 저장 이후에 수행하는 연결과 같은 순서다.
        method.assignOwner(ownerType);
        method.assignSourceSpan(10, 20);

        entityManager.flush();
        entityManager.clear();

        SymbolEntity reloaded = symbolRepository.findById(method.getSymbolId()).orElseThrow();

        assertEquals(10, reloaded.getSourceStartLine());
        assertEquals(20, reloaded.getSourceEndLine());
        assertNotNull(reloaded.getOwner());
        assertEquals(ownerType.getSymbolId(), reloaded.getOwner().getSymbolId());
    }

    @Test
    @DisplayName("이미 저장된 symbol을 다시 save해도 예외 없이 갱신된다")
    void updatesAlreadyPersistedSymbolWithoutError() {
        RepoRun run = persistRun();

        SymbolEntity symbol = newSymbol(run, "com.example.Baz", SymbolKind.TYPE);
        symbolRepository.saveAll(List.of(symbol));

        entityManager.flush();
        entityManager.clear();

        // 재적재 경로: DB에서 다시 읽어온 엔티티를 save()로 다시 저장한다.
        SymbolEntity loaded = symbolRepository.findById(symbol.getSymbolId()).orElseThrow();
        loaded.assignSourceSpan(30, 40);

        assertDoesNotThrow(() -> {
            symbolRepository.save(loaded);
            entityManager.flush();
        });

        entityManager.clear();

        SymbolEntity reloaded = symbolRepository.findById(symbol.getSymbolId()).orElseThrow();
        assertEquals(30, reloaded.getSourceStartLine());
        assertEquals(40, reloaded.getSourceEndLine());
    }

    private RepoRun persistRun() {
        RepoRun run = new RepoRun(
                RUN_ID,
                null,
                "https://github.com/example/repo.git",
                "example",
                "repo",
                "main",
                "0123456789abcdef0123456789abcdef01234567",
                RunStatus.QUEUED,
                null,
                null,
                null,
                null,
                null,
                null
        );

        entityManager.persist(run);
        return run;
    }

    private SymbolEntity newSymbol(RepoRun run, String qualifiedName, SymbolKind kind) {
        return new SymbolEntity(
                "sym_" + Integer.toHexString(qualifiedName.hashCode()),
                run,
                null,
                kind,
                qualifiedName,
                qualifiedName,
                AccessLevel.PUBLIC,
                JsonNodeFactory.instance.arrayNode(),
                null,
                JsonNodeFactory.instance.objectNode(),
                null,
                null,
                null,
                OriginKind.AST,
                null,
                null,
                null,
                JsonNodeFactory.instance.arrayNode()
        );
    }
}
