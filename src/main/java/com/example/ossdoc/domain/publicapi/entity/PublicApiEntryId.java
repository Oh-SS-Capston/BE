package com.example.ossdoc.domain.publicapi.entity;

import com.example.ossdoc.global.apiPayload.code.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class PublicApiEntryId implements Serializable {

    @Column(name = "run_id")
    private String runId;

    @Column(name = "symbol_id")
    private String symbolId;
}