package com.auditpro.roadtosale.repo;

import com.auditpro.roadtosale.domain.Session;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * A page of a user's sessions in their dealership, newest first (PRD §7 #12).
     * Pagination is enforced by the caller (sane default + hard cap) so the list
     * can never be unbounded (W1).
     */
    List<Session> findByDealershipIdAndUserIdOrderByStartedAtDesc(UUID dealershipId, UUID userId, Pageable pageable);

    /**
     * Tenant-scoped single fetch (PRD §7 #6-7). A session in another dealership
     * yields empty here, which callers translate to 404 — never leak existence.
     */
    Optional<Session> findByIdAndDealershipId(UUID id, UUID dealershipId);
}
