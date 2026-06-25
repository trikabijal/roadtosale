package com.auditpro.roadtosale.security;

import java.util.UUID;

/**
 * The authenticated principal resolved from the access token. {@code dealershipId}
 * here is the ONLY authoritative tenant — never read tenancy from request bodies.
 */
public record AuthenticatedUser(UUID userId, UUID dealershipId) {
}
