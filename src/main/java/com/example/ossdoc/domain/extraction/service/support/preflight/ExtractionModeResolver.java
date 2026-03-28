package com.example.ossdoc.domain.extraction.service.support.preflight;

import com.example.ossdoc.domain.build.dto.json.BuildManifest;
import com.example.ossdoc.domain.build.enums.BuildMode;
import com.example.ossdoc.domain.extraction.dto.request.FactsExtractRequest;
import com.example.ossdoc.domain.extraction.enums.ExtractionMode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ExtractionModeResolver {

    public ExtractionModeResolutionResult resolve(
            FactsExtractRequest request,
            BuildManifest buildManifest,
            BuildOutputVerifier.BuildOutputVerificationResult buildVerificationResult,
            BytecodeAvailabilityChecker.BytecodeAvailabilityResult bytecodeAvailabilityResult
    ) {
        List<String> warnings = new ArrayList<>();

        ExtractionMode requestedMode = request == null ? null : request.mode();
        ExtractionMode manifestMode = deriveManifestMode(buildManifest);
        ExtractionMode candidateMode = requestedMode != null ? requestedMode : manifestMode;

        boolean fallbackApplied = false;
        String fallbackReason = null;
        ExtractionMode resolvedMode = candidateMode;

        if (candidateMode == null) {
            warnings.add("No requested mode and no manifest-derived mode were available");
            return new ExtractionModeResolutionResult(
                    requestedMode,
                    manifestMode,
                    null,
                    false,
                    null,
                    List.copyOf(warnings)
            );
        }

        boolean buildFull = buildManifest != null && buildManifest.getBuildMode() == BuildMode.FULL;
        boolean buildUsable = buildVerificationResult != null && buildVerificationResult.buildUsable();
        boolean hasUsableBytecode = bytecodeAvailabilityResult != null && bytecodeAvailabilityResult.hasUsableBytecode();
        boolean partialBytecode = bytecodeAvailabilityResult != null && bytecodeAvailabilityResult.partialAvailability();

        if (candidateMode == ExtractionMode.AST_PLUS_BYTECODE
                || candidateMode == ExtractionMode.AST_PLUS_PARTIAL_BYTECODE) {
            if (!buildFull) {
                resolvedMode = ExtractionMode.AST_ONLY;
                fallbackApplied = true;
                fallbackReason = "Bytecode-backed extraction requires BuildMode.FULL";
                warnings.add("Requested bytecode extraction but buildMode is not FULL, so AST_ONLY fallback was applied");
            } else if (!buildUsable) {
                resolvedMode = ExtractionMode.AST_ONLY;
                fallbackApplied = true;
                fallbackReason = "Build metadata is not usable for extraction";
                warnings.add("Requested bytecode extraction but build metadata is not usable, so AST_ONLY fallback was applied");
            } else if (!hasUsableBytecode) {
                resolvedMode = ExtractionMode.AST_ONLY;
                fallbackApplied = true;
                fallbackReason = "No usable .class files were found";
                warnings.add("Requested bytecode extraction but no usable .class files were found, so AST_ONLY fallback was applied");
            } else if (partialBytecode) {
                resolvedMode = ExtractionMode.AST_PLUS_PARTIAL_BYTECODE;
                warnings.add("Partial bytecode availability detected; ASM extraction will run only on existing class roots");
            } else {
                resolvedMode = ExtractionMode.AST_PLUS_BYTECODE;
            }
        }

        if (candidateMode == ExtractionMode.AST_ONLY
                && buildVerificationResult != null
                && !buildVerificationResult.hasSourceMetadata()) {
            warnings.add("AST_ONLY was selected but no sourceRoots/testRoots metadata was recorded");
        }

        if (buildManifest != null
                && buildManifest.getBuildMode() != null
                && buildManifest.getBuildMode() != BuildMode.FULL
                && bytecodeAvailabilityResult != null
                && bytecodeAvailabilityResult.hasUsableBytecode()) {
            warnings.add("Bytecode exists on disk but buildMode is not FULL; extraction will not trust it automatically");
        }

        return new ExtractionModeResolutionResult(
                requestedMode,
                manifestMode,
                resolvedMode,
                fallbackApplied,
                fallbackReason,
                List.copyOf(warnings)
        );
    }

    private ExtractionMode deriveManifestMode(BuildManifest buildManifest) {
        if (buildManifest == null || buildManifest.getBuildMode() == null) {
            return null;
        }

        return switch (buildManifest.getBuildMode()) {
            case FULL -> ExtractionMode.AST_PLUS_BYTECODE;
            case COMPILE_ONLY, SOURCE_ONLY -> ExtractionMode.AST_ONLY;
            case FAILED -> null;
        };
    }

    public record ExtractionModeResolutionResult(
            ExtractionMode requestedMode,
            ExtractionMode manifestMode,
            ExtractionMode resolvedMode,
            boolean fallbackApplied,
            String fallbackReason,
            List<String> warnings
    ) {
        public ExtractionModeResolutionResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
