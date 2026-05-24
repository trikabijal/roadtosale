package com.checkSheet.DAO;

import com.checkSheet.entity.NpdMaster;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Repository
public class NpdMasterDAO {

    @Autowired
    private EntityManager entityManager;

    public NpdMaster findByChecksheetIdAndDateAndShift(Long checksheetId, Date npdDate, String shift) {
        try {
            StringBuilder sql = new StringBuilder("""
                    SELECT *
                    FROM npd_master
                    WHERE checksheet_id = :checksheetId
                      AND npd_date = :npdDate
                    """);
            if (shift != null) {
                sql.append(" AND shift = :shift");
            } else {
                sql.append(" AND shift IS NULL");
            }
            Query q = entityManager.createNativeQuery(sql.toString(), NpdMaster.class)
                    .setParameter("checksheetId", checksheetId)
                    .setParameter("npdDate", npdDate);
            if (shift != null) {
                q.setParameter("shift", shift);
            }
            return (NpdMaster) q.getSingleResult();
        } catch (Exception e) {
            return null;
        }
    }

    public List<NpdMaster> findByChecksheetIdAndDateRange(Long checksheetId, Date startDate, Date endDate) {
        try {
            String sql = """
                    SELECT *
                    FROM npd_master
                    WHERE checksheet_id = :checksheetId
                      AND npd_date BETWEEN :startDate AND :endDate
                    """;
            Query q = entityManager.createNativeQuery(sql, NpdMaster.class)
                    .setParameter("checksheetId", checksheetId)
                    .setParameter("startDate", startDate)
                    .setParameter("endDate", endDate);
            @SuppressWarnings("unchecked")
            List<NpdMaster> results = q.getResultList();
            return results;
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> aggregatedCounts(String checksheetNameLike,
                                                String frequencyOfCheck,
                                                Date startDate,
                                                Date endDate,
                                                int page,
                                                int size) {
        String base = """
                FROM npd_master nm
                JOIN checksheets cs ON cs.id = nm.checksheet_id
                WHERE 1=1
                """;

        StringBuilder where = new StringBuilder();
        Map<String, Object> params = new HashMap<>();

        if (checksheetNameLike != null && !checksheetNameLike.trim().isEmpty()) {
            where.append(" AND LOWER(cs.name) LIKE :name");
            params.put("name", "%" + checksheetNameLike.trim().toLowerCase() + "%");
        }
        if (frequencyOfCheck != null && !frequencyOfCheck.trim().isEmpty()) {
            where.append(" AND cs.frequency_of_check = :freq");
            params.put("freq", frequencyOfCheck.trim());
        }
        if (startDate != null) {
            where.append(" AND nm.npd_date >= :startDate");
            params.put("startDate", startDate);
        }
        if (endDate != null) {
            where.append(" AND nm.npd_date <= :endDate");
            params.put("endDate", endDate);
        }

        String countSql = "SELECT COUNT(1) FROM (SELECT cs.id " + base + where + " GROUP BY cs.id) t";
        Query countQuery = entityManager.createNativeQuery(countSql);
        params.forEach(countQuery::setParameter);
        Number totalGroups = (Number) countQuery.getSingleResult();

        String dataSql = "SELECT cs.id AS checksheet_id, cs.name AS checksheet_name, cs.frequency_of_check AS frequency_of_check, COUNT(nm.id) AS npd_count " +
                base + where +
                " GROUP BY cs.id, cs.name, cs.frequency_of_check " +
                " ORDER BY cs.name ASC " +
                " OFFSET :offset LIMIT :limit";
        Query dataQuery = entityManager.createNativeQuery(dataSql);
        params.forEach(dataQuery::setParameter);
        dataQuery.setParameter("offset", page * size);
        dataQuery.setParameter("limit", size);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();

        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", totalGroups.longValue());
        return result;
    }

    public Map<String, Object> findByChecksheetPaginated(Long checksheetId, int page, int size, Date startDate, Date endDate) {
        StringBuilder where = new StringBuilder(" WHERE nm.checksheet_id = :checksheetId ");
        if (startDate != null) {
            where.append(" AND nm.npd_date >= :startDate");
        }
        if (endDate != null) {
            where.append(" AND nm.npd_date <= :endDate");
        }

        String countSql = "SELECT COUNT(1) FROM npd_master nm" + where.toString();
        Query countQuery = entityManager.createNativeQuery(countSql)
                .setParameter("checksheetId", checksheetId);
        if (startDate != null) countQuery.setParameter("startDate", startDate);
        if (endDate != null) countQuery.setParameter("endDate", endDate);
        Number total = (Number) countQuery.getSingleResult();

        String dataSql = "SELECT nm.id, nm.npd_date, nm.shift, nm.checksheet_id, nm.remarks, nm.created_at " +
                " FROM npd_master nm" + where.toString() +
                " ORDER BY nm.npd_date DESC, nm.id DESC OFFSET :offset LIMIT :limit";
        Query dataQuery = entityManager.createNativeQuery(dataSql)
                .setParameter("checksheetId", checksheetId)
                .setParameter("offset", page * size)
                .setParameter("limit", size);
        if (startDate != null) dataQuery.setParameter("startDate", startDate);
        if (endDate != null) dataQuery.setParameter("endDate", endDate);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();

        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total.longValue());
        return result;
    }
}


