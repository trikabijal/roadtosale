package com.checkSheet.repository;

import com.checkSheet.entity.ChksHeaderData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface ChksHeaderDataRepository extends JpaRepository<ChksHeaderData, Long> {

    @Override
    Optional<ChksHeaderData> findById(Long id);

    @Modifying
    @Query("DELETE FROM ChksHeaderData d WHERE d.checksheet.id = :checksheetId")
    void deleteByChecksheet_Id(@Param("checksheetId") Long checksheetId);

    List<ChksHeaderData> findByChecksheet_IdOrderById(Long checksheetId);

    @Query("SELECT chd FROM ChksHeaderData chd WHERE chd.checksheet.id = :checksheetId and chd.chksHeaderData.id = :chksHeaderDataId " +
            " order by chd.orderNo, chd.id ")
    List<ChksHeaderData> findByChecksheet_IdAndChecksheetChksHeaderDataOrderById(Long checksheetId, Long chksHeaderDataId);

    @Query("SELECT chd FROM ChksHeaderData chd WHERE chd.checksheet.id = :checksheetId and chd.chksHeaderData.id is null " +
            " order by chd.orderNo, chd.id ")
    List<ChksHeaderData> findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(Long checksheetId);

    @Query("SELECT COUNT(chd) FROM ChksHeaderData chd WHERE chd.chksHeaderData.id = :parentId")
    long countChildrenByParentId(@Param("parentId") Long parentId);

    List<ChksHeaderData> findByChksHeaderDataId(Long parentId);

    @Modifying
    @Query("DELETE FROM ChksHeaderData d WHERE d.id IN :ids")
    void deleteAllByIdIn(@Param("ids") List<Long> ids);
}
