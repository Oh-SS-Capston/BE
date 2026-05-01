package com.example.ossdoc.domain.cluster.support;

import org.springframework.stereotype.Component;

@Component
public class ScoreNormalizer {

    public double normalize(double value, double max) {
        if (max <= 0.0) return 0.0;
        return value / max;
    }
}