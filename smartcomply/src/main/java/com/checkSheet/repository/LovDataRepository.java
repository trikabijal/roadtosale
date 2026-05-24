package com.checkSheet.repository;

import com.checkSheet.entity.LovData;
import com.checkSheet.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LovDataRepository extends JpaRepository<LovData, Long> {

    @Override
    Optional<LovData> findById(Long id);

    Optional<LovData> findByName(String name);
}
