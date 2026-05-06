package com.example.ossdoc.domain.extraction.service.support.merge;

import com.example.ossdoc.domain.extraction.dto.model.ChunkResult;
import com.example.ossdoc.domain.extraction.dto.model.StatsMeta;
import com.example.ossdoc.domain.extraction.dto.model.SymbolFact;
import com.example.ossdoc.domain.extraction.enums.SymbolKind;

import java.util.concurrent.atomic.LongAdder;

/**
 * 추출 중간 통계를 누적하고 마지막에 StatsMeta로 변환
 */
public class StatsAccumulator {

    private final LongAdder filesScanned = new LongAdder();
    private final LongAdder filesParsed = new LongAdder();
    private final LongAdder filesSkipped = new LongAdder();

    private final LongAdder types = new LongAdder();
    private final LongAdder constructors = new LongAdder();
    private final LongAdder methods = new LongAdder();
    private final LongAdder fields = new LongAdder();

    private final LongAdder relations = new LongAdder();
    private final LongAdder observations = new LongAdder();
    private final LongAdder evidence = new LongAdder();
    private final LongAdder unresolvedTypeRefs = new LongAdder();
    private final LongAdder totalTypeRefs = new LongAdder();
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

    public void recordChunkResult(ChunkResult chunkResult) {
        if (chunkResult == null) {
            return;
        }
        merge(chunkResult.stats());
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

    public void recordTotalTypeRef() {
        totalTypeRefs.increment();
    }

    public void recordError() {
        errors.increment();
    }

    public void recordSymbol(SymbolFact symbolFact) {
        if (symbolFact == null || symbolFact.kind() == null) {
            return;
        }

        SymbolKind kind = symbolFact.kind();
        switch (kind) {
            case TYPE -> recordType();
            case CONSTRUCTOR -> recordConstructor();
            case METHOD -> recordMethod();
            case FIELD -> recordField();
        }
    }

    public void merge(StatsMeta stats) {
        if (stats == null) {
            return;
        }

        filesScanned.add(stats.filesScanned());
        filesParsed.add(stats.filesParsed());
        filesSkipped.add(stats.filesSkipped());
        types.add(stats.types());
        constructors.add(stats.constructors());
        methods.add(stats.methods());
        fields.add(stats.fields());
        relations.add(stats.relations());
        observations.add(stats.observations());
        evidence.add(stats.evidence());
        unresolvedTypeRefs.add(stats.unresolvedTypeRefsTotal());
        totalTypeRefs.add(stats.totalTypeRefsTotal());
        errors.add(stats.errors());
    }

    public StatsMeta snapshot() {
        long unresolvedTotal = unresolvedTypeRefs.sum();
        long totalTypeRefsSum = totalTypeRefs.sum();
        double ratio = totalTypeRefsSum > 0 ? (double) unresolvedTotal / totalTypeRefsSum : 0.0;

        return StatsMeta.builder()
                .filesScanned(filesScanned.sum())
                .filesParsed(filesParsed.sum())
                .filesSkipped(filesSkipped.sum())
                .types(types.sum())
                .constructors(constructors.sum())
                .methods(methods.sum())
                .fields(fields.sum())
                .relations(relations.sum())
                .observations(observations.sum())
                .evidence(evidence.sum())
                .unresolvedTypeRatio(ratio)
                .unresolvedTypeRefsTotal(unresolvedTotal)
                .totalTypeRefsTotal(totalTypeRefsSum)
                .errors(errors.sum())
                .build();
    }
}
