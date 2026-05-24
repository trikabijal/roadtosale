package com.checkSheet.DAO;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import com.checkSheet.DTO.*;
import com.checkSheet.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class DashboardDAO {

    @Autowired
    private EntityManager entityManager;

    public List<DashboardDTO> getRespectedChecksheet(DashboardDTO dashboardDTO, List<Long> sectionIds, User curUser) {
        try {
            String selectQuery = "SELECT DISTINCT c.id, c.version, c.name ";
            StringBuilder baseQuery = new StringBuilder(
                " FROM checksheets c " +
                " LEFT JOIN inspections uc ON c.id = uc.checksheet_id " +
                " WHERE c.status in ('APPROVED')"
            );
            String roleWiseQry = "";
            // Permission-based filtering: null = global access (no filter), non-empty = scoped access
            if(sectionIds != null && !sectionIds.isEmpty()) {
                roleWiseQry += " c.department_id in (:sectionIds) OR ";
                roleWiseQry += " c.preparer_user_id = :curUserId ";
                roleWiseQry += " OR :curUserId = ANY(c.validator_user_ids)";
                roleWiseQry += " OR :curUserId = ANY(c.approver_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_validator_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_approver_user_ids) ";
            }
            if (!Objects.isNull(dashboardDTO.getStartDate()) && !Objects.isNull(dashboardDTO.getEndDate())) {
                baseQuery.append(" AND (c.expiry_date is null OR c.expiry_date > :startDate) ");
                baseQuery.append(" AND DATE(uc.submitted_at) BETWEEN :startDate AND :endDate ");
            }

            if (!Objects.isNull(dashboardDTO.getFrequencyOfCheck())) {
                baseQuery.append(" AND c.frequency_of_check = :frequencyOfCheck ");
            }
            if (!Objects.isNull(dashboardDTO.getIsDataValidator()) && dashboardDTO.getIsDataValidator()) {
                baseQuery.append(" AND :loginUserId = ANY(c.data_validator_user_ids) ");
            }
            if(!roleWiseQry.isBlank()){
                baseQuery.append(" AND (" + roleWiseQry + ")");
            }

            baseQuery.append(" ORDER BY c.id DESC ");

            Query query = entityManager.createNativeQuery(
                    selectQuery + baseQuery,
                "getDashboardChecksheets"
            );
            // Permission-based filtering: set parameters when sectionIds is not null and not empty
            if(sectionIds != null && !sectionIds.isEmpty()) {
                query.setParameter("sectionIds", sectionIds);
                if(!roleWiseQry.isBlank()) {
                    query.setParameter("curUserId", curUser.getId());
                }
            }
            if (!Objects.isNull(dashboardDTO.getStartDate()) && !Objects.isNull(dashboardDTO.getEndDate())) {
                query.setParameter("startDate", dashboardDTO.getStartDate());
                query.setParameter("endDate", dashboardDTO.getEndDate());
            }
            if (!Objects.isNull(dashboardDTO.getIsDataValidator()) && dashboardDTO.getIsDataValidator()) {
                query.setParameter("loginUserId", curUser.getId());
            }

            if (!Objects.isNull(dashboardDTO.getFrequencyOfCheck())) {
                query.setParameter("frequencyOfCheck", dashboardDTO.getFrequencyOfCheck());
            }

            return query.getResultList();

        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<DashboardDTO> getRespectedQuestions(List<Long> checksheetIds) {
        try {
            String nativeQuery = """
                SELECT DISTINCT 
                    cq.id, 
                    cq.name 
                FROM chks_questions cq 
                WHERE cq.checksheet_id IN (:checksheetIds) 
                AND cq.deleted_at IS NULL 
                ORDER BY cq.id ASC
            """;

            Query query = entityManager.createNativeQuery(nativeQuery, "getDashboardQuestions")
                .setParameter("checksheetIds", checksheetIds);

            return query.getResultList();
        } catch (Exception e) {
//            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<DashboardDTO> getChecksheetSummaryData(DashboardDTO dashboardDTO) {
        try {
            StringBuilder queryBuilder = new StringBuilder("""
                SELECT 
                    cq.id,
                    cq.name,
                    COUNT(CASE WHEN ucaj.judgement = 'OK' THEN 1 END) as ok_count,
                    COUNT(CASE WHEN ucaj.judgement = 'NOT OK' THEN 1 END) as not_ok_count
                FROM (select * from chks_questions cq where cq.id IN (:questionIds)) cq
                JOIN (select * from usr_chksheet_ans_judgements ucaj where ucaj.chks_question_id IN (:questionIds)) ucaj ON cq.id = ucaj.chks_question_id
                JOIN (select * from inspections uc where submitted_at is not null
                    AND DATE(uc.submitted_at) BETWEEN :startDate AND :endDate) uc ON ucaj.inspection_id = uc.id 
                    AND uc.submitted_at IS NOT NULL                    
                JOIN (select * from checksheets c where c.frequency_of_check = :frequencyOfCheck) c ON uc.checksheet_id = c.id 
                
                GROUP BY cq.id, cq.name 
                ORDER BY cq.id ASC
            """);


            Query query = entityManager.createNativeQuery(queryBuilder.toString(), "getChecksheetSummaryData")
                .setParameter("questionIds", dashboardDTO.getQuestionIds())
                .setParameter("startDate", dashboardDTO.getStartDate()) 
                .setParameter("endDate", dashboardDTO.getEndDate())
                .setParameter("frequencyOfCheck", dashboardDTO.getFrequencyOfCheck());

            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<ChksQuestionResultDTO> getQuestionResultsByQuestionIds(List<Long> questionIds) {
        try {
            StringBuilder queryBuilder = new StringBuilder("""
                SELECT 
                    cqr.id,
                    cqr.checksheet_id,
                    cqr.chks_header_id,
                    cqr.chks_question_id,
                    cqr.answer_type,
                    cqr.objective_type,
                    cqr.upper_limit,
                    cqr.lower_limit,
                    cqr.unit,
                    cqr.matrix_name,
                    cqr.matrix_row_header_names,
                    cqr.matrix_column_header_names,
                    cqr.no_of_results,
                    cqr.no_of_rows,
                    cqr.no_of_columns,
                    cqr.chks_matrix_row_name,
                    cqr.chks_matrix_col_name,
                    cqr.is_optional
                FROM chks_question_results cqr
                WHERE cqr.chks_question_id IN (:questionIds)
            """);


            Query query = entityManager.createNativeQuery(queryBuilder.toString(), "getResultData")
                    .setParameter("questionIds", questionIds);

            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<UserChecksheetAnswerDTO> getChecksheetSummaryAnswers(DashboardDTO dashboardDTO) {
        try {
            String queryBuilder = """
                        SELECT 
                            uca.id, uca.inspection_id, uca.chks_question_result_id, uca.answer, uca.chks_question_rslt_option_id, uca.chks_question_id, uca.judgement
                        FROM (select * from user_checksheet_answers where chks_question_id IN (:questionIds)) uca
                        JOIN (select * from inspections where submitted_at is not null
                            AND DATE(submitted_at) BETWEEN :startDate AND :endDate) uc ON uca.inspection_id = uc.id
                    """;


            Query query = entityManager.createNativeQuery(queryBuilder, "getChecksheetSummaryAnswers")
                    .setParameter("questionIds", dashboardDTO.getQuestionIds())
                    .setParameter("startDate", dashboardDTO.getStartDate())
                    .setParameter("endDate", dashboardDTO.getEndDate());

            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<UsrChecksheetAnsJudgementDTO> getChecksheetSummaryQueJudgements(DashboardDTO dashboardDTO) {
        try {
            StringBuilder queryBuilder = new StringBuilder("""
                SELECT 
                    ucaj.inspection_id, ucaj.chks_question_id, ucaj.judgement, uc.submitted_at, u.first_name, u.last_name, u.username
                FROM (select * from usr_chksheet_ans_judgements where chks_question_id IN (:questionIds)) ucaj
                JOIN (select * from inspections where submitted_at is not null
                    AND DATE(submitted_at) BETWEEN :startDate AND :endDate) uc ON ucaj.inspection_id = uc.id
                JOIN users u on u.id = uc.created_by
            """);


            Query query = entityManager.createNativeQuery(queryBuilder.toString(), "getChecksheetSummaryQueJudgements")
                    .setParameter("questionIds", dashboardDTO.getQuestionIds())
                    .setParameter("startDate", dashboardDTO.getStartDate())
                    .setParameter("endDate", dashboardDTO.getEndDate());

            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<Object[]> getPlanVsActualData(List<Long> checksheetIds, Date startDate, Date endDate) {
        try {
            String nativeQuery = """
                SELECT 
                    c.id as checksheet_id,
                    c.name as checksheet_name,
                    DATE(uc.started_at) as check_date,
                    uc.status,
                    c.implementation_date,
                    c.frequency_of_freq_of_chk,
                    c.frequency_of_check,
                    uc.shift
                FROM (select * from checksheets c where c.id IN (:checksheetIds)) c
                LEFT JOIN (select * from inspections uc where uc.started_at is not null
                    AND DATE(uc.started_at) BETWEEN :startDate AND :endDate 
                    AND uc.checksheet_id IN (:checksheetIds)) uc ON c.id = uc.checksheet_id 

                ORDER BY c.id, check_date
            """;

            Query query = entityManager.createNativeQuery(nativeQuery)
                .setParameter("checksheetIds", checksheetIds)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);

            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    public List<DashboardDTO> getRespectedChecksheetHeaderData(Long checksheetId, Long chksHeaderDataId) {
        try {
            StringBuilder queryBuilder = new StringBuilder()
                .append("SELECT chd.name, chd.id, chd.chks_header_id, chd.chks_header_data_id, chd.level ")
                .append(" FROM chks_header_data chd ")
                .append(" WHERE chd.checksheet_id = :checksheetId ");

            if (chksHeaderDataId != null) {
                queryBuilder.append(" AND chd.chks_header_data_id = :chksHeaderDataId ");
            } else {
                queryBuilder.append(" AND chd.chks_header_data_id is null ");
            }
            
            queryBuilder.append("ORDER BY chd.id");

            Query query = entityManager.createNativeQuery(queryBuilder.toString(), "getRespectedChecksheetHeaderData");
            query.setParameter("checksheetId", checksheetId);
            
            if (chksHeaderDataId != null) {
                query.setParameter("chksHeaderDataId", chksHeaderDataId);
            }

            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    public List<DashboardDTO> getRespectedChecksheetQuestionsData(Long chksHeaderDataId) {
        try {
            String nativeQuery = """
                SELECT DISTINCT 
                    cq.id,
                    cq.name,
                    cq.chks_header_data_id
                FROM chks_questions cq
                WHERE cq.chks_header_data_id = :chksHeaderDataId
                AND cq.deleted_at IS NULL
                ORDER BY cq.id ASC
            """;

            Query query = entityManager.createNativeQuery(nativeQuery, "getRespectedChecksheetQuestionsData")
                .setParameter("chksHeaderDataId", chksHeaderDataId);

            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<TrendChartDTO> getTrendChartData(TrendChartDTO trendChartDTO) {
        try {
            String sql = """
                SELECT 
                    uc.id,
                    uca.id as user_checksheet_answers_id,
                    uca.chks_question_id,
                    uca.judgement as judgement,
                    DATE(uc.started_at) as check_date,
                    uca.created_at as answer_date,
                    uc.submitted_at as submitted_at,                    
                    uc.status,
                    uca.answer,
                    u.first_name,
                    u.last_name,
                    u.username
                FROM (select * from user_checksheet_answers uca 
                    where uca.chks_question_id in (:chksQuestionIds)) uca
                JOIN (select * from chks_question_results cqr
                    WHERE cqr.chks_header_id in (:chksQuestionResultIds)  
                    AND chks_question_id in (:chksQuestionIds)
                    ) cqr on uca.chks_question_result_id = cqr.id
                JOIN (select * from inspections uc 
                    where uc.started_at is not null
                    AND DATE(uc.started_at) BETWEEN :startDate AND :endDate
                    AND uc.checksheet_id IN (:checksheetIds)) uc 
                    ON uc.id = uca.inspection_id 
                LEFT JOIN users u on u.id = uc.operator_user_id
                ORDER BY uca.chks_question_id, check_date
            """;

            Query query = entityManager.createNativeQuery(sql, "getTrendChartData")
                .setParameter("chksQuestionResultIds", trendChartDTO.getChksQuestionResultIds())
                .setParameter("chksQuestionIds", trendChartDTO.getChksQuestionIds())
                .setParameter("startDate", trendChartDTO.getStartDate())
                .setParameter("endDate", trendChartDTO.getEndDate())
                .setParameter("checksheetIds", trendChartDTO.getChecksheetIds());

            return query.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public CompletionFunnelDTO getCompletionFunnelData(CompletionFunnelDTO request, List<Long> sectionIds, User curUser) {
        try {
            StringBuilder baseQuery = new StringBuilder(
                "SELECT " +
                "  COALESCE(SUM(CASE WHEN uc.id IS NULL THEN 1 ELSE 0 END), 0) as planned, " +
                "  COALESCE(SUM(CASE WHEN uc.status = 'IN_PROGRESS' THEN 1 ELSE 0 END), 0) as in_progress, " +
                "  COALESCE(SUM(CASE WHEN uc.status = 'SUBMITTED' THEN 1 ELSE 0 END), 0) as submitted, " +
                "  COALESCE(SUM(CASE WHEN uc.status = 'VALIDATED' THEN 1 ELSE 0 END), 0) as validated, " +
                "  COALESCE(SUM(CASE WHEN uc.status = 'APPROVED' THEN 1 ELSE 0 END), 0) as approved, " +
                "  COALESCE(SUM(CASE WHEN uc.status IN ('NOT_APPROVED', 'INVALIDATED') THEN 1 ELSE 0 END), 0) as rejected " +
                "FROM checksheets c " +
                "LEFT JOIN inspections uc ON c.id = uc.checksheet_id " +
                "  AND DATE(uc.submitted_at) BETWEEN :startDate AND :endDate " +
                "WHERE c.status = 'APPROVED' "
            );

            // Permission-based filtering: null = global access (no filter), non-empty = scoped access
            String roleWiseQry = "";
            if (sectionIds != null && !sectionIds.isEmpty()) {
                roleWiseQry += " c.department_id IN (:sectionIds) OR ";
                roleWiseQry += " c.preparer_user_id = :curUserId ";
                roleWiseQry += " OR :curUserId = ANY(c.validator_user_ids)";
                roleWiseQry += " OR :curUserId = ANY(c.approver_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_validator_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_approver_user_ids) ";
            }

            if (request.getDepartmentIds() != null && !request.getDepartmentIds().isEmpty()) {
                baseQuery.append(" AND c.department_id IN (:departmentIds) ");
            }

            if (request.getFrequencyOfCheck() != null) {
                baseQuery.append(" AND c.frequency_of_check = :frequencyOfCheck ");
            }

            if (!roleWiseQry.isBlank()) {
                baseQuery.append(" AND (" + roleWiseQry + ")");
            }

            Query query = entityManager.createNativeQuery(baseQuery.toString());
            query.setParameter("startDate", request.getStartDate());
            query.setParameter("endDate", request.getEndDate());

            if (request.getDepartmentIds() != null && !request.getDepartmentIds().isEmpty()) {
                query.setParameter("departmentIds", request.getDepartmentIds());
            }

            if (request.getFrequencyOfCheck() != null) {
                query.setParameter("frequencyOfCheck", request.getFrequencyOfCheck());
            }

            // Permission-based filtering: set parameters when sectionIds is not null and not empty
            if (sectionIds != null && !sectionIds.isEmpty()) {
                query.setParameter("sectionIds", sectionIds);
                if (!roleWiseQry.isBlank()) {
                    query.setParameter("curUserId", curUser.getId());
                }
            }

            Object[] result = (Object[]) query.getSingleResult();
            
            CompletionFunnelDTO response = new CompletionFunnelDTO();
            response.setPlanned(((Number) result[0]).longValue());
            response.setInProgress(((Number) result[1]).longValue());
            response.setSubmitted(((Number) result[2]).longValue());
            response.setValidated(((Number) result[3]).longValue());
            response.setApproved(((Number) result[4]).longValue());
            response.setRejected(((Number) result[5]).longValue());

            return response;
        } catch (Exception e) {
            e.printStackTrace();
            return new CompletionFunnelDTO();
        }
    }

    public List<ComplianceHeatmapDTO> getComplianceHeatmapData(ComplianceHeatmapDTO request, List<Long> sectionIds, User curUser) {
        try {
            String periodFormat = "MONTH".equals(request.getGroupBy()) 
                ? "TO_CHAR(uc.submitted_at, 'YYYY-MM')" 
                : "TO_CHAR(uc.submitted_at, 'IYYY-IW')";

            StringBuilder baseQuery = new StringBuilder(
                "SELECT " +
                "  d.id as department_id, " +
                "  d.name as department_name, " +
                "  " + periodFormat + " as period, " +
                "  COUNT(DISTINCT uc.id) as total_checksheets, " +
                "  SUM(CASE WHEN j.judgement = 'OK' THEN 1 ELSE 0 END) as ok_count, " +
                "  SUM(CASE WHEN j.judgement = 'NOT OK' THEN 1 ELSE 0 END) as not_ok_count " +
                "FROM departments d " +
                "JOIN checksheets c ON d.id = c.department_id " +
                "JOIN inspections uc ON c.id = uc.checksheet_id " +
                "LEFT JOIN usr_chksheet_ans_judgements j ON uc.id = j.inspection_id " +
                "WHERE uc.status = 'APPROVED' " +
                "  AND DATE(uc.submitted_at) BETWEEN :startDate AND :endDate "
            );

            // Permission-based filtering: null = global access (no filter), non-empty = scoped access
            String roleWiseQry = "";
            if (sectionIds != null && !sectionIds.isEmpty()) {
                roleWiseQry += " c.department_id IN (:sectionIds) OR ";
                roleWiseQry += " c.preparer_user_id = :curUserId ";
                roleWiseQry += " OR :curUserId = ANY(c.validator_user_ids)";
                roleWiseQry += " OR :curUserId = ANY(c.approver_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_validator_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_approver_user_ids) ";
            }

            if (!roleWiseQry.isBlank()) {
                baseQuery.append(" AND (" + roleWiseQry + ")");
            }

            baseQuery.append(" GROUP BY d.id, d.name, period ");
            baseQuery.append(" ORDER BY d.name, period ");

            Query query = entityManager.createNativeQuery(baseQuery.toString());
            query.setParameter("startDate", request.getStartDate());
            query.setParameter("endDate", request.getEndDate());

            // Permission-based filtering: set parameters when sectionIds is not null and not empty
            if (sectionIds != null && !sectionIds.isEmpty()) {
                query.setParameter("sectionIds", sectionIds);
                if (!roleWiseQry.isBlank()) {
                    query.setParameter("curUserId", curUser.getId());
                }
            }

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();
            
            // Group by department
            java.util.Map<Long, ComplianceHeatmapDTO> departmentMap = new java.util.HashMap<>();
            for (Object[] row : results) {
                Long deptId = ((Number) row[0]).longValue();
                String deptName = (String) row[1];
                String period = (String) row[2];
                Long totalChecksheets = ((Number) row[3]).longValue();
                Long okCount = ((Number) row[4]).longValue();
                Long notOkCount = ((Number) row[5]).longValue();
                
                Double complianceScore = (okCount + notOkCount) > 0 
                    ? (okCount.doubleValue() / (okCount + notOkCount)) * 100.0 
                    : 0.0;

                ComplianceHeatmapDTO.PeriodData periodData = new ComplianceHeatmapDTO.PeriodData(
                    period, complianceScore, totalChecksheets, okCount, notOkCount
                );

                if (!departmentMap.containsKey(deptId)) {
                    ComplianceHeatmapDTO dto = new ComplianceHeatmapDTO();
                    dto.setDepartmentId(deptId);
                    dto.setDepartmentName(deptName);
                    dto.setPeriods(new ArrayList<>());
                    departmentMap.put(deptId, dto);
                }
                departmentMap.get(deptId).getPeriods().add(periodData);
            }

            return new ArrayList<>(departmentMap.values());
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<RecentSubmissionDTO> getRecentSubmissions(RecentSubmissionDTO request, List<Long> sectionIds, User curUser) {
        try {
            StringBuilder baseQuery = new StringBuilder(
                "SELECT " +
                "  uc.id as inspection_id, " +
                "  c.id as checksheet_id, " +
                "  c.name as checksheet_name, " +
                "  c.version, " +
                "  d.id as department_id, " +
                "  d.name as department_name, " +
                "  u.first_name, " +
                "  u.last_name, " +
                "  u.username, " +
                "  uc.status, " +
                "  uc.submitted_at, " +
                "  COUNT(CASE WHEN j.judgement = 'OK' THEN 1 END) as ok_count, " +
                "  COUNT(CASE WHEN j.judgement = 'NOT OK' THEN 1 END) as not_ok_count, " +
                "  uc.shift, " +
                "  c.frequency_of_check " +
                "FROM inspections uc " +
                "JOIN checksheets c ON uc.checksheet_id = c.id " +
                "JOIN users u ON uc.created_by = u.id " +
                "JOIN departments d ON c.department_id = d.id " +
                "LEFT JOIN usr_chksheet_ans_judgements j ON uc.id = j.inspection_id " +
                "WHERE uc.submitted_at IS NOT NULL " +
                "  AND DATE(uc.submitted_at) BETWEEN :startDate AND :endDate "
            );

            // Permission-based filtering: null = global access (no filter), non-empty = scoped access
            String roleWiseQry = "";
            if (sectionIds != null && !sectionIds.isEmpty()) {
                roleWiseQry += " c.department_id IN (:sectionIds) OR ";
                roleWiseQry += " c.preparer_user_id = :curUserId ";
                roleWiseQry += " OR :curUserId = ANY(c.validator_user_ids)";
                roleWiseQry += " OR :curUserId = ANY(c.approver_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_validator_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_approver_user_ids) ";
            }

            if (request.getDepartmentIds() != null && !request.getDepartmentIds().isEmpty()) {
                baseQuery.append(" AND c.department_id IN (:departmentIds) ");
            }

            if (request.getStatusFilter() != null && !request.getStatusFilter().isEmpty()) {
                baseQuery.append(" AND uc.status IN (:statusFilter) ");
            }

            if (!roleWiseQry.isBlank()) {
                baseQuery.append(" AND (" + roleWiseQry + ")");
            }

            baseQuery.append(" GROUP BY uc.id, c.id, c.name, c.version, d.id, d.name, ");
            baseQuery.append(" u.first_name, u.last_name, u.username, uc.status, uc.submitted_at, uc.shift, c.frequency_of_check ");
            baseQuery.append(" ORDER BY uc.submitted_at DESC ");
            
            int limit = request.getLimit() != null ? request.getLimit() : 50;
            int offset = request.getOffset() != null ? request.getOffset() : 0;
            baseQuery.append(" LIMIT :limit OFFSET :offset ");

            Query query = entityManager.createNativeQuery(baseQuery.toString());
            query.setParameter("startDate", request.getStartDate());
            query.setParameter("endDate", request.getEndDate());
            query.setParameter("limit", limit);
            query.setParameter("offset", offset);

            if (request.getDepartmentIds() != null && !request.getDepartmentIds().isEmpty()) {
                query.setParameter("departmentIds", request.getDepartmentIds());
            }

            if (request.getStatusFilter() != null && !request.getStatusFilter().isEmpty()) {
                query.setParameter("statusFilter", request.getStatusFilter());
            }

            // Permission-based filtering: set parameters when sectionIds is not null and not empty
            if (sectionIds != null && !sectionIds.isEmpty()) {
                query.setParameter("sectionIds", sectionIds);
                if (!roleWiseQry.isBlank()) {
                    query.setParameter("curUserId", curUser.getId());
                }
            }

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();
            
            List<RecentSubmissionDTO> dtoList = new ArrayList<>();
            for (Object[] row : results) {
                RecentSubmissionDTO dto = new RecentSubmissionDTO(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).longValue(),
                    (String) row[2],
                    ((Number) row[3]).longValue(),
                    ((Number) row[4]).longValue(),
                    (String) row[5],
                    (String) row[6],
                    (String) row[7],
                    (String) row[8],
                    (String) row[9],
                    (Date) row[10],
                    row[11] != null ? ((Number) row[11]).longValue() : 0L,
                    row[12] != null ? ((Number) row[12]).longValue() : 0L,
                    (String) row[13],
                    (String) row[14]
                );
                dtoList.add(dto);
            }

            return dtoList;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<NonConformingQuestionDTO> getTopNonConformingQuestions(NonConformingQuestionDTO request, List<Long> sectionIds, User curUser) {
        try {
            StringBuilder baseQuery = new StringBuilder(
                "SELECT " +
                "  q.id as question_id, " +
                "  c.id as checksheet_id, " +
                "  c.name as checksheet_name, " +
                "  q.name as question_name, " +
                "  q.description as question_description, " +
                "  COUNT(*) as total_count, " +
                "  SUM(CASE WHEN j.judgement = 'NOT OK' THEN 1 ELSE 0 END) as not_ok_count " +
                "FROM usr_chksheet_ans_judgements j " +
                "JOIN chks_questions q ON j.chks_question_id = q.id " +
                "JOIN inspections uc ON j.inspection_id = uc.id " +
                "JOIN checksheets c ON q.checksheet_id = c.id " +
                "WHERE uc.status = 'APPROVED' " +
                "  AND DATE(uc.submitted_at) BETWEEN :startDate AND :endDate "
            );

            // Permission-based filtering: null = global access (no filter), non-empty = scoped access
            String roleWiseQry = "";
            if (sectionIds != null && !sectionIds.isEmpty()) {
                roleWiseQry += " c.department_id IN (:sectionIds) OR ";
                roleWiseQry += " c.preparer_user_id = :curUserId ";
                roleWiseQry += " OR :curUserId = ANY(c.validator_user_ids)";
                roleWiseQry += " OR :curUserId = ANY(c.approver_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_validator_user_ids) ";
                roleWiseQry += " OR :curUserId = ANY(c.data_approver_user_ids) ";
            }

            if (request.getChecksheetIds() != null && !request.getChecksheetIds().isEmpty()) {
                baseQuery.append(" AND c.id IN (:checksheetIds) ");
            }

            if (!roleWiseQry.isBlank()) {
                baseQuery.append(" AND (" + roleWiseQry + ")");
            }

            baseQuery.append(" GROUP BY q.id, c.id, c.name, q.name, q.description ");
            baseQuery.append(" HAVING SUM(CASE WHEN j.judgement = 'NOT OK' THEN 1 ELSE 0 END) > 0 ");
            baseQuery.append(" ORDER BY (SUM(CASE WHEN j.judgement = 'NOT OK' THEN 1 ELSE 0 END) * 100.0 / COUNT(*)) DESC, ");
            baseQuery.append("          SUM(CASE WHEN j.judgement = 'NOT OK' THEN 1 ELSE 0 END) DESC ");
            
            int limit = request.getLimit() != null ? request.getLimit() : 20;
            baseQuery.append(" LIMIT :limit ");

            Query query = entityManager.createNativeQuery(baseQuery.toString());
            query.setParameter("startDate", request.getStartDate());
            query.setParameter("endDate", request.getEndDate());
            query.setParameter("limit", limit);

            if (request.getChecksheetIds() != null && !request.getChecksheetIds().isEmpty()) {
                query.setParameter("checksheetIds", request.getChecksheetIds());
            }

            // Permission-based filtering: set parameters when sectionIds is not null and not empty
            if (sectionIds != null && !sectionIds.isEmpty()) {
                query.setParameter("sectionIds", sectionIds);
                if (!roleWiseQry.isBlank()) {
                    query.setParameter("curUserId", curUser.getId());
                }
            }

            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();
            
            List<NonConformingQuestionDTO> dtoList = new ArrayList<>();
            for (Object[] row : results) {
                NonConformingQuestionDTO dto = new NonConformingQuestionDTO(
                    ((Number) row[0]).longValue(),
                    ((Number) row[1]).longValue(),
                    (String) row[2],
                    (String) row[3],
                    (String) row[4],
                    ((Number) row[5]).longValue(),
                    ((Number) row[6]).longValue()
                );
                dtoList.add(dto);
            }

            return dtoList;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
} 