package com.checkSheet.repository;

import com.checkSheet.entity.APIHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface APIHistoryRepository extends JpaRepository<APIHistory, Long> {

    Optional<APIHistory> findById(Long id);

    @Transactional
    @Modifying
    @Query("DELETE FROM APIHistory a WHERE a.createdAt < :cutoffDate")
    void deleteAllByCreatedDateBefore(LocalDate cutoffDate);
}
