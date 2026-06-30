package com.auditpro.roadtosale.dto;

/** {@code POST /sessions/{id}/submit} body — the optional final transcript. */
public record SubmitSessionRequest(String transcript) {
}
