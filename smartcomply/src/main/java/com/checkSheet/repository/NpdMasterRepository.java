package com.checkSheet.repository;

import com.checkSheet.entity.NpdMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface NpdMasterRepository extends JpaRepository<NpdMaster, Long> {
    Optional<NpdMaster> findByChecksheet_IdAndNpdDateAndShift(Long checksheetId, Date npdDate, String shift);
}


