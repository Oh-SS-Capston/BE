package com.example.ossdoc.domain.graphstore.dto.facts.raw;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RawSymbolTableDto {

    private List<RawSymbolFactDto> modules = new ArrayList<>();
    private List<RawSymbolFactDto> packages = new ArrayList<>();
    private List<RawSymbolFactDto> types = new ArrayList<>();
    private List<RawSymbolFactDto> constructors = new ArrayList<>();
    private List<RawSymbolFactDto> methods = new ArrayList<>();
    private List<RawSymbolFactDto> fields = new ArrayList<>();
}