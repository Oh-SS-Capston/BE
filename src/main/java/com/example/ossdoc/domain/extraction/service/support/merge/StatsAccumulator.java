package com.example.ossdoc.domain.extraction.service.support.merge;

import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.enums.ChunkKind;
import com.example.ossdoc.domain.extraction.enums.ChunkStatus;
import com.example.ossdoc.domain.extraction.enums.SymbolFactKind;

import java.util.concurrent.atomic.LongAdder;

/**
 * 추출 중간 통계를 누적하고 마지막에 StatsMeta로 변환
 */
public class StatsAccumulator {

    private final LongAdder filesScanned = new LongAdder();
    private final LongAdder filesParsed = new LongAdder();
    private final LongAdder filesSkipped = new LongAdder();

    private final LongAdder astFilesScanned = new LongAdder();
    private final LongAdder classFilesScanned = new LongAdder();
    private final LongAdder astFilesParsed = new LongAdder();
    private final LongAdder classFilesParsed = new LongAdder();

    private final LongAdder chunksTotal = new LongAdder();
    private final LongAdder chunksSucceeded = new LongAdder();
    private final LongAdder chunksFailed = new LongAdder();
    private final LongAdder chunksPartial = new LongAdder();

    private final LongAdder types = new LongAdder();
    private final LongAdder constructors = new LongAdder();
    private final LongAdder methods = new LongAdder();
    private final LongAdder fields = new LongAdder();

    private final LongAdder edgeCandidates = new LongAdder();
    private final LongAdder relations = new LongAdder();
    private final LongAdder observations = new LongAdder();
    private final LongAdder evidence = new LongAdder();
    private final LongAdder unresolvedTypeRefs = new LongAdder();
    private final LongAdder errors = new LongAdder();

    public void recordFileScanned() {
        filesScanned.increment();
    }

    public void recordFileParsed() {
        filesParsed.increment();
    }

    public void recordFileSkipped() {
        filesSkipped.increment();
    }

    public void recordAstFileScanned() {
        recordFileScanned();
        astFilesScanned.increment();
    }

    public void recordClassFileScanned() {
        recordFileScanned();
        classFilesScanned.increment();
    }

    public void recordAstFileParsed() {
        recordFileParsed();
        astFilesParsed.increment();
    }

    public void recordClassFileParsed() {
        recordFileParsed();
        classFilesParsed.increment();
    }

    public void recordChunkPlanned() {
        chunksTotal.increment();
    }

    public void recordChunkStatus(ChunkStatus status) {
        if (status == null) {
            return;
        }
        switch (status) {
            case SUCCEEDED -> chunksSucceeded.increment();
            case FAILED -> chunksFailed.increment();
            case PARTIAL -> chunksPartial.increment();
            case PENDING, RUNNING -> {
                // snapshot 시점 집계에서는 별도 카운트하지 않음
            }
        }
    }

    public void recordChunkResult(ChunkResult chunkResult) {
        if (chunkResult == null) {
            return;
        }

        recordChunkPlanned();
        recordChunkStatus(chunkResult.status());
        merge(chunkResult.stats());
    }

    public void recordScannedFileKind(ChunkKind kind) {
        if (kind == null) {
            recordFileScanned();
            return;
        }
        if (kind == ChunkKind.AST) {
            recordAstFileScanned();
        } else {
            recordClassFileScanned();
        }
    }

    public void recordParsedFileKind(ChunkKind kind) {
        if (kind == null) {
            recordFileParsed();
            return;
        }
        if (kind == ChunkKind.AST) {
            recordAstFileParsed();
        } else {
            recordClassFileParsed();
        }
    }

    public void recordType() {
        types.increment();
    }

    public void recordConstructor() {
        constructors.increment();
    }

    public void recordMethod() {
        methods.increment();
    }

    public void recordField() {
        fields.increment();
    }

    public void recordRelation() {
        relations.increment();
        edgeCandidates.increment();
    }

    public void recordObservation() {
        observations.increment();
    }

    public void recordEvidence() {
        evidence.increment();
    }

    public void recordUnresolvedTypeRef() {
        unresolvedTypeRefs.increment();
    }

    public void recordError() {
        errors.increment();
    }

    public void recordSymbol(SymbolFact symbolFact) {
        if (symbolFact == null || symbolFact.kind() == null) {
            return;
        }

        SymbolFactKind kind = symbolFact.kind();
        switch (kind) {
            case TYPE -> recordType();
            case CONSTRUCTOR -> recordConstructor();
            case METHOD -> recordMethod();
            case FIELD -> recordField();
            case MODULE, PACKAGE -> {
                // 현재 StatsMeta에는 module/package 카운트 필드가 없음
            }
        }
    }

    public void merge(StatsMeta stats) {
        if (stats == null) {
            return;
        }

        filesScanned.add(stats.filesScanned());
        filesParsed.add(stats.filesParsed());
        filesSkipped.add(stats.filesSkipped());
        astFilesScanned.add(stats.astFilesScanned());
        classFilesScanned.add(stats.classFilesScanned());
        astFilesParsed.add(stats.astFilesParsed());
        classFilesParsed.add(stats.classFilesParsed());
        chunksTotal.add(stats.chunksTotal());
        chunksSucceeded.add(stats.chunksSucceeded());
        chunksFailed.add(stats.chunksFailed());
        chunksPartial.add(stats.chunksPartial());
        types.add(stats.types());
        constructors.add(stats.constructors());
        methods.add(stats.methods());
        fields.add(stats.fields());
        edgeCandidates.add(stats.edgeCandidates());
        relations.add(stats.relations());
        observations.add(stats.observations());
        evidence.add(stats.evidence());
        unresolvedTypeRefs.add(stats.unresolvedTypeRefs());
        errors.add(stats.errors());
    }

    public StatsMeta snapshot() {
        return StatsMeta.builder()
                .filesScanned(filesScanned.sum())
                .filesParsed(filesParsed.sum())
                .filesSkipped(filesSkipped.sum())
                .astFilesScanned(astFilesScanned.sum())
                .classFilesScanned(classFilesScanned.sum())
                .astFilesParsed(astFilesParsed.sum())
                .classFilesParsed(classFilesParsed.sum())
                .chunksTotal(chunksTotal.sum())
                .chunksSucceeded(chunksSucceeded.sum())
                .chunksFailed(chunksFailed.sum())
                .chunksPartial(chunksPartial.sum())
                .types(types.sum())
                .constructors(constructors.sum())
                .methods(methods.sum())
                .fields(fields.sum())
                .edgeCandidates(edgeCandidates.sum())
                .relations(relations.sum())
                .observations(observations.sum())
                .evidence(evidence.sum())
                .unresolvedTypeRefs(unresolvedTypeRefs.sum())
                .errors(errors.sum())
                .build();
    }
}
