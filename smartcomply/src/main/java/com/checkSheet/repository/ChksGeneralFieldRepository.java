package com.checkSheet.repository;

import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.ChksGeneralField;
import com.checkSheet.entity.ChksHeader;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksGeneralFieldRepository extends JpaRepository<ChksGeneralField, Long> {

    @Override
    Optional<ChksGeneralField> findById(Long id);

    List<ChksGeneralField> findByChecksheet_IdOrderById(Long checksheetId);

    void deleteByChecksheet_Id(Long checksheetId);
}
