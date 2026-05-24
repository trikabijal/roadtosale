package com.checkSheet.DAO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import com.checkSheet.DTO.ChecksheetDTO;
import com.checkSheet.constant.ChecksheetStatusType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class ChecksheetDAO {

    @Autowired
    private EntityManager entityManager;

    public List<ChecksheetDTO> getChecksheets(Long userId, String columnName) {
        try {
            String query = "SELECT c.id, c.name FROM checksheets c where 1 = 1 ";
            if(!Objects.isNull(userId) && !Objects.isNull(columnName) && columnName.trim().length() > 0) {
                query += " and '" + userId + "' = ANY(c." + columnName + ")   ";
            }
            List<ChecksheetDTO> result = (List<ChecksheetDTO>) entityManager.createNativeQuery(query, "getChecksheets").getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<String> getChecksheetNames(Long userId, String columnName, List<Long> checksheetIds) {
        try {
            String query = "SELECT c.name FROM checksheets c where 1 = 1 ";
            if(!Objects.isNull(userId) && !Objects.isNull(columnName) && columnName.trim().length() > 0) {
                query += " and '" + userId + "' = ANY(c." + columnName + ")   ";
            }
            if(checksheetIds != null && !checksheetIds.isEmpty()){
                String chksIds = checksheetIds.stream()
                                            .map(String::valueOf)
                                            .collect(Collectors.joining(","));
                query += " and id in ("+chksIds+")";
            }
            List<String> result = (List<String>) entityManager.createNativeQuery(query).getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<ChecksheetDTO> getChecksheetDetails(Long userId, String columnName, List<Long> checksheetIds) {
        try {
            // Logic: Join checksheets with departments.
            // If d.department_id is null, it's a department (sectionName = null).
            // If d.department_id is NOT null, it's a section. d.name is section, pd.name is department.
            String query = "SELECT c.id, c.name, d.id as dept_id, d.name as name1, pd.id as parent_id, pd.name as name2 " + 
                           " FROM checksheets c " +
                           " LEFT JOIN departments d ON c.department_id = d.id " +
                           " LEFT JOIN departments pd ON d.department_id = pd.id " +
                           " WHERE 1 = 1 ";
            
            if(!Objects.isNull(userId) && !Objects.isNull(columnName) && columnName.trim().length() > 0) {
                query += " and '" + userId + "' = ANY(c." + columnName + ")   ";
            }
            if(checksheetIds != null && !checksheetIds.isEmpty()){
                String chksIds = checksheetIds.stream()
                                            .map(String::valueOf)
                                            .collect(Collectors.joining(","));
                query += " and c.id in ("+chksIds+")";
            }
            
            List<Object[]> rows = entityManager.createNativeQuery(query).getResultList();
            List<ChecksheetDTO> dtos = new ArrayList<>();
            for(Object[] row : rows) {
                ChecksheetDTO dto = new ChecksheetDTO();
                dto.setId(row[0] != null ? Long.parseLong(row[0].toString()) : null);
                dto.setName(row[1] != null ? row[1].toString() : null);
                
                // Determine Dept/Section names
                Long deptId = row[2] != null ? Long.parseLong(row[2].toString()) : null;
                String name1 = row[3] != null ? row[3].toString() : null;
                Long parentId = row[4] != null ? Long.parseLong(row[4].toString()) : null;
                String name2 = row[5] != null ? row[5].toString() : null;
                
                if (parentId != null) {
                    // It's a section
                    dto.setSectionName(name1);
                    dto.setDepartmentName(name2);
                    dto.setDepartmentId(parentId);
                } else {
                    // It's a department
                    dto.setDepartmentName(name1);
                    dto.setSectionName(null);
                    dto.setDepartmentId(deptId);
                }
                
                dtos.add(dto);
            }
            return dtos;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public Page<ChecksheetDTO> getRespectedChecksheet(ChecksheetDTO checksheetDTO, Long currentPage, Long perPageRecord) {
        try {
            String countQuery = "";
            String actualQuery = "";
            String nativeQuery = "";
            actualQuery = "select c.id,c.name,c.approver_user_ids " +
                    " ,c.created_at,c.data_approver_user_ids " +
                    ",c.data_validator_user_ids,c.description,c.implementation_date,c.expiry_date,c.model_no, \n" +
                    " c.version,c.serial_number,c.status,c.validator_user_ids,c.preparer_user_id,c.escalation_guidelines_days,c.alert_to_user_ids,c.escalate_to_user_ids, \n" +
                    " c.frequency_of_check,c.uid as uid,c.operator_user_ids, c.is_file_upload ";
            countQuery = " select count(*)  \n";
            nativeQuery += " from checksheets c where 1=1  \n";
            if (!Objects.isNull(checksheetDTO.getCurrentUserId())) {
                nativeQuery += " and (" + checksheetDTO.getCurrentUserId() + " = ANY(c.approver_user_ids) " +
                        " or " + checksheetDTO.getCurrentUserId() + " = ANY(c.validator_user_ids)" +
                        " or preparer_user_id = '" + checksheetDTO.getCurrentUserId() + "' ";
                if(!Objects.isNull(checksheetDTO.getDepartmentIds())) {
                    nativeQuery += " or c.department_id in :departmentIds";
                }
                nativeQuery += ") ";
            }
            if(!Objects.isNull(checksheetDTO.getSearch()) && !checksheetDTO.getSearch().trim().isEmpty()) {
                String searchVal = "'%" + checksheetDTO.getSearch().toLowerCase() + "%'";
                nativeQuery += " and (lower(c.name) like " + searchVal + " " +
                        " or EXISTS (select 1 from users u where (lower(u.username) like " + searchVal + " " +
                        " or lower(u.first_name) like " + searchVal + " " +
                        " or lower(u.last_name) like " + searchVal + " " +
                        " or lower(u.email) like " + searchVal + " " +
                        " or lower(concat(u.first_name, ' ', u.last_name)) like " + searchVal + ") " +
                        " and (u.id = c.preparer_user_id " +
                        " or u.id = ANY(c.approver_user_ids) " +
                        " or u.id = ANY(c.validator_user_ids) " +
                        " or u.id = ANY(c.operator_user_ids) " +
                        " or u.id = ANY(c.data_approver_user_ids) " +
                        " or u.id = ANY(c.data_validator_user_ids) " +
                        " or u.id = ANY(c.alert_to_user_ids) " +
                        " or u.id = ANY(c.escalate_to_user_ids) " +
                        " or u.id = ANY(c.waiting_user_ids) " +
                        " or u.id = c.alert_to_user_id " +
                        " or u.id = c.escalate_to_user_id " +
                        "))) ";
            }
            if(!Objects.isNull(checksheetDTO.getStatus()) && !checksheetDTO.getStatus().trim().isEmpty()) {
                if(checksheetDTO.getStatus().equals("NEW")){
                    nativeQuery += " and ( c.status != 'APPROVED')  ";
                } else {
                    nativeQuery += " and ( c.status = '" + checksheetDTO.getStatus() + "')  ";
                }
            }
            if(checksheetDTO.getWaitingChks()){
                nativeQuery += " and  "+checksheetDTO.getCurrentUserId()+" = ANY(c.waiting_user_ids)  ";
            }

            countQuery += nativeQuery;
            nativeQuery += " order by c.id desc limit " + perPageRecord + " OFFSET " + currentPage * perPageRecord + " ";
            actualQuery += nativeQuery;
            Pageable pageable = PageRequest.of(Math.toIntExact(currentPage), Math.toIntExact(perPageRecord));
            Long totalCount = count(countQuery, checksheetDTO);
            Query query = entityManager.createNativeQuery(actualQuery, "getRespectedChecksheet");
            if(!Objects.isNull(checksheetDTO.getDepartmentIds())) {
                query.setParameter("departmentIds", checksheetDTO.getDepartmentIds());
            }
            System.out.println(actualQuery);
            List<ChecksheetDTO> result = (List<ChecksheetDTO>) query.getResultList();
            return new PageImpl<>(result, pageable, totalCount);
        } catch (Exception e) {
            e.printStackTrace();
            Pageable pageable = PageRequest.of(Math.toIntExact(currentPage), Math.toIntExact(perPageRecord));
            return new PageImpl<>(new ArrayList<>(), pageable, 0L);
        }
    }

    public ChecksheetDTO getRespectedChecksheetDetail(ChecksheetDTO checksheetDTO) {
        try {
            String nativeQuery = "select c.id, c.name, c.approver_user_ids, " +
                    " c.created_at, c.data_approver_user_ids, " +
                    " c.data_validator_user_ids, c.description, c.implementation_date,c.expiry_date, c.model_no, " +
                    " c.version, c.serial_number, c.status, c.validator_user_ids, c.preparer_user_id, " +
                    " c.escalation_guidelines_days, c.alert_to_user_ids, c.escalate_to_user_ids, " +
                    " c.frequency_of_check, c.frequency_of_freq_of_chk, c.operator_user_ids, c.department_id, c.is_file_upload, " +
                    " c.asset_code, c.checksheet_type, c.is_corporate, c.uid, c.version_remark " +
                    " from checksheets c where c.id in :ids ";
            Query query = entityManager.createNativeQuery(nativeQuery, "getRespectedChecksheetDetail");
            if(!Objects.isNull(checksheetDTO.getIds())) {
                query.setParameter("ids", checksheetDTO.getIds());
            }
            ChecksheetDTO result = (ChecksheetDTO) query.getSingleResult();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new ChecksheetDTO();
        }
    }

    public Long count(String query, ChecksheetDTO checksheetDTO) {
        Query nativeQuery = entityManager.createNativeQuery(query);
        if(!Objects.isNull(checksheetDTO.getDepartmentIds())) {
            nativeQuery.setParameter("departmentIds", checksheetDTO.getDepartmentIds());
        }
        Long result = (Long) nativeQuery
                .getSingleResult();
        return result;
    }

    public List<ChecksheetDTO> getUserChecksheets(Long userId, String columnName, ChecksheetDTO checksheetDTO) {
        try {
            String query = "SELECT c.id,c.name,c.model_no,c.frequency_of_check,c.frequency_of_freq_of_chk,c.uid,c.expiry_date " +
                    "FROM checksheets c where c.status = '" + ChecksheetStatusType.APPROVED.name() + "' and DATE(c.implementation_date) <= CURRENT_DATE";
            if(!Objects.isNull(userId) && !Objects.isNull(columnName) && columnName.trim().length() > 0) {
                query += " and '" + userId + "' = ANY(c." + columnName + ")   ";
            }
            if(!Objects.isNull(checksheetDTO) && !Objects.isNull(checksheetDTO.getSearch()) && !checksheetDTO.getSearch().trim().isEmpty()){
                query += " and (c.name like '%"+checksheetDTO.getSearch().trim()+"%' OR c.model_no like '%"+checksheetDTO.getSearch().trim()+"%')";
            }
            List<ChecksheetDTO> result = (List<ChecksheetDTO>) entityManager.createNativeQuery(query, "getOperatorChecksheet").getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public boolean isUserAssignedToOtherChecksheets(Long userId, Long departmentId, Long currentChecksheetId, String columnName) {
        try {
            String query = "SELECT COUNT(*) > 0 FROM checksheets c " +
                    " WHERE c.department_id = :departmentId " +
                    " AND c.id != :currentChecksheetId " +
                    " AND :userId = ANY(c." + columnName + ") ";

            Query nativeQuery = entityManager.createNativeQuery(query);
            nativeQuery.setParameter("departmentId", departmentId);
            nativeQuery.setParameter("currentChecksheetId", currentChecksheetId);
            nativeQuery.setParameter("userId", userId);

            return (Boolean) nativeQuery.getSingleResult();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
