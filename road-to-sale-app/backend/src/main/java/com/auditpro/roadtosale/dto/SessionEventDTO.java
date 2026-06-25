package com.auditpro.roadtosale.dto;

import com.auditpro.roadtosale.domain.EventSource;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** A single detected cue event in a batch. {@code source} uses lowercase wire values. */
public record SessionEventDTO(
        @NotBlank String cueId,
        @NotBlank String questionId,
        @NotNull @Min(1) Integer stepNo,
        @NotNull OffsetDateTime detectedAt,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
        String transcriptSpan,
        @NotNull EventSource source) {
}
