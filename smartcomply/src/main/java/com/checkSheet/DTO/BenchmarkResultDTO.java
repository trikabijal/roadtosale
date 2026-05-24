package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BenchmarkResultDTO {
    private String provider;
    private String model;
    private String judgement;
    private String explanation;
    private Double confidence;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cachedInputTokens;
    private Long latencyMs;
    private Double estimatedCostUsd;
    private String error; // null if successful, error message if failed
}
