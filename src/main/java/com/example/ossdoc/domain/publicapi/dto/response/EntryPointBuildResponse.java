package com.example.ossdoc.domain.publicapi.dto.response;

import com.example.ossdoc.domain.publicapi.model.EntryPointCandidate;
import com.example.ossdoc.domain.publicapi.service.EntryPointDetectService;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class EntryPointBuildResponse {

    private final int totalCount;
    private final int highCount;
    private final int medCount;
    private final int lowCount;

    public static EntryPointBuildResponse of(List<EntryPointCandidate> candidates) {
        int high = 0, med = 0, low = 0;
        for (EntryPointCandidate c : candidates) {
            int rank = EntryPointDetectService.confidenceRank(c.getConfidence());
            if (rank == 3) high++;
            else if (rank == 2) med++;
            else if (rank == 1) low++;
        }
        return EntryPointBuildResponse.builder()
                .totalCount(candidates.size())
                .highCount(high)
                .medCount(med)
                .lowCount(low)
                .build();
    }
}
