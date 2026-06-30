package com.auditpro.roadtosale.repo;

import com.auditpro.roadtosale.domain.Dealership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DealershipRepository extends JpaRepository<Dealership, UUID> {

    Optional<Dealership> findByName(String name);
}
