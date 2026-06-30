package com.auditpro.roadtosale.repo;

import com.auditpro.roadtosale.domain.Photo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    List<Photo> findBySessionIdOrderByUploadedAtAsc(UUID sessionId);

    /** Scoped fetch: the photo must belong to the given (already tenant-checked) session. */
    Optional<Photo> findByIdAndSessionId(UUID id, UUID sessionId);
}
