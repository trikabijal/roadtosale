package com.auditpro.roadtosale.service;

import com.auditpro.roadtosale.domain.Session;
import com.auditpro.roadtosale.repo.SessionRepository;
import com.auditpro.roadtosale.security.AuthenticatedUser;
import com.auditpro.roadtosale.web.ApiException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Single definition of the tenant-scoping security primitive.
 *
 * <p>"Does the caller own this session?" is a security boundary, so it must have
 * exactly ONE implementation (N6). Both {@link SessionService} and
 * {@link PhotoService} resolve sessions through here. Any miss — including a
 * session that belongs to another dealership — yields a 404 so existence is
 * never leaked across tenants (PRD §7 #6-7).
 */
@Component
public class SessionAccess {

    private final SessionRepository sessionRepository;

    public SessionAccess(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /** Tenant-scoped fetch: any miss (incl. another dealership's id) -> 404. */
    public Session requireOwnedSession(AuthenticatedUser caller, UUID sessionId) {
        return sessionRepository.findByIdAndDealershipId(sessionId, caller.dealershipId())
                .orElseThrow(() -> new ApiException.NotFound("Session not found"));
    }
}
