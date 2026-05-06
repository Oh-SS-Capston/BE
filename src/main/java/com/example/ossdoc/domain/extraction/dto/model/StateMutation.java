package com.example.ossdoc.domain.extraction.dto.model;

import com.example.ossdoc.domain.extraction.enums.MutationKind;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StateMutation(
        @JsonProperty("kind") MutationKind kind,
        @JsonProperty("target") String target,
        @JsonProperty("sequence_index") int sequenceIndex,
        @JsonProperty("is_conditional") boolean isConditional
) {}
