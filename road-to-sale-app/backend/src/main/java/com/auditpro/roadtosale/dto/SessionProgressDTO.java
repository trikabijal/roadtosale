package com.auditpro.roadtosale.dto;

/** {@code answered} = questions satisfied; {@code total} = questions in the checksheet. */
public record SessionProgressDTO(int answered, int total) {
}
