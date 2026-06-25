package com.auditpro.roadtosale.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** {@code POST /sessions/{id}/events} body — a batch of cue events. */
public record PostEventsRequest(@NotEmpty @Valid List<SessionEventDTO> events) {
}
