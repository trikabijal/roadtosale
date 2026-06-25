package com.auditpro.roadtosale.repo;

import com.auditpro.roadtosale.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Login lookup is by username only (usernames are seeded unique across tenants). */
    Optional<User> findByUsername(String username);
}
