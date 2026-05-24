package com.checkSheet.DAO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import com.checkSheet.DTO.ChecksheetDTO;
import com.checkSheet.DTO.UserChecksheetDTO;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@Repository
public class UserChecksheetDAO {

    @Autowired
    private EntityManager entityManager;

    public Page<ChecksheetDTO> getRespectedUserChecksheet(ChecksheetDTO checksheetDTO, Long currentPage, Long perPageRecord) {
        try {
            String countQuery = "";
            String actualQuery = "";
            String nativeQuery = "", chksFilter = "", dateFilter ="";
            if(!Objects.isNull(checksheetDTO.getIds()) && !checksheetDTO.getIds().isEmpty()){
                chksFilter = " where id in (:chksIds)";
            }
            if(checksheetDTO.getStartDate() != null  && checksheetDTO.getEndDate() != null){
                dateFilter = " AND DATE(uc.submitted_at) BETWEEN :startDate AND :endDate ";
            }
            actualQuery = "select uc.id,c.name,c.approver_user_ids " +
                    " ,c.created_at,c.data_approver_user_ids " +
                    ",c.data_validator_user_ids,c.description,c.implementation_date,c.model_no, \n" +
                    " c.version,c.serial_number,uc.status,c.validator_user_ids,c.preparer_user_id,c.escalation_guidelines_days,c.alert_to_user_ids,c.escalate_to_user_ids, \n" +
                    " c.frequency_of_check,c.uid as uid,c.operator_user_ids, c.is_file_upload," +
                    " uc.created_by as operator_id, u.first_name as operator_first_name, u.last_name as operator_last_name, u.username as operator_username," +
                    " uc.started_at, uc.submitted_at, uc.shift  ";
            countQuery = " select count(*)  \n";
            nativeQuery += " from (select * from checksheets c "+chksFilter+") c inner join " +
                    " (select * from inspections uc where uc.status not in('IN_PROGRESS','DECLINED') "+dateFilter+") uc on uc.checksheet_id = c.id left join "+
                    " (select * from users u) u on u.id = uc.created_by where 1=1 \n";
            if (!Objects.isNull(checksheetDTO.getCurrentUserId())) {
                nativeQuery += " and (" + checksheetDTO.getCurrentUserId() + " = ANY(c.data_validator_user_ids) " +
                        " or " + checksheetDTO.getCurrentUserId() + " = ANY(c.data_approver_user_ids))";
            }
            boolean hasSearch = !Objects.isNull(checksheetDTO.getSearch()) && !checksheetDTO.getSearch().trim().isEmpty();
            if(hasSearch) {
                nativeQuery += " and (lower(c.name) like :search)  ";
            }
            boolean hasStatus = !Objects.isNull(checksheetDTO.getStatus()) && !checksheetDTO.getStatus().trim().isEmpty();
            if(hasStatus) {
                nativeQuery += " and ( uc.status = :status)  ";
            }

            if(checksheetDTO.getWaitingUsrChks()){
                nativeQuery += " and  "+checksheetDTO.getCurrentUserId()+" = ANY(uc.waiting_user_ids)  ";
            }else if(!Objects.isNull(checksheetDTO.getDepartmentIds())) {
                nativeQuery += " and  (c.department_id in :departmentIds)  ";
            }

            countQuery += nativeQuery;
            nativeQuery += " order by c.id desc limit " + perPageRecord + " OFFSET " + currentPage * perPageRecord + " ";
            actualQuery += nativeQuery;
            Pageable pageable = PageRequest.of(Math.toIntExact(currentPage), Math.toIntExact(perPageRecord));
            Long totalCount = count(countQuery, checksheetDTO);
            Query query = entityManager.createNativeQuery(actualQuery, "getRespectedUserChecksheet");
            if(!Objects.isNull(checksheetDTO.getIds()) && !checksheetDTO.getIds().isEmpty()) {
                query.setParameter("chksIds",checksheetDTO.getIds());
            }
            if(!checksheetDTO.getWaitingUsrChks() && !Objects.isNull(checksheetDTO.getDepartmentIds())) {
                query.setParameter("departmentIds", checksheetDTO.getDepartmentIds());
            }
            if(!dateFilter.isBlank()){
                query.setParameter("startDate", checksheetDTO.getStartDate());
                query.setParameter("endDate", checksheetDTO.getEndDate());
            }
            if(hasSearch) {
                query.setParameter("search", "%" + checksheetDTO.getSearch().toLowerCase() + "%");
            }
            if(hasStatus) {
                query.setParameter("status", checksheetDTO.getStatus());
            }
            List<ChecksheetDTO> result = (List<ChecksheetDTO>) query.getResultList();
            return new PageImpl<>(result, pageable, totalCount);
        } catch (Exception e) {
            e.printStackTrace();
            Pageable pageable = PageRequest.of(Math.toIntExact(currentPage), Math.toIntExact(perPageRecord));
            return new PageImpl<>(new ArrayList<>(), pageable, 0L);
        }
    }

    public Long count(String query, ChecksheetDTO checksheetDTO) {
        Query nativeQuery = entityManager.createNativeQuery(query);
        if(!checksheetDTO.getWaitingUsrChks() && !Objects.isNull(checksheetDTO.getDepartmentIds())) {
            nativeQuery.setParameter("departmentIds", checksheetDTO.getDepartmentIds());
        }
        if(!Objects.isNull(checksheetDTO.getIds()) && !checksheetDTO.getIds().isEmpty()) {
            nativeQuery.setParameter("chksIds",checksheetDTO.getIds());
        }
        if(checksheetDTO.getStartDate() != null  && checksheetDTO.getEndDate() != null) {
            nativeQuery.setParameter("startDate", checksheetDTO.getStartDate());
            nativeQuery.setParameter("endDate", checksheetDTO.getEndDate());
        }
        if(!Objects.isNull(checksheetDTO.getSearch()) && !checksheetDTO.getSearch().trim().isEmpty()) {
            nativeQuery.setParameter("search", "%" + checksheetDTO.getSearch().toLowerCase() + "%");
        }
        if(!Objects.isNull(checksheetDTO.getStatus()) && !checksheetDTO.getStatus().trim().isEmpty()) {
            nativeQuery.setParameter("status", checksheetDTO.getStatus());
        }
        Long result = (Long) nativeQuery
                .getSingleResult();
        return result;
    }

    public List<UserChecksheetDTO> getDeclinedUserChecksheets(Long userId) {
        try {
            String query = "SELECT id,status,checksheet_id,shift,started_at,submitted_at,submission_version FROM inspections where operator_user_id = :userId and status = 'DECLINED'";
            Query nativeQuery = entityManager.createNativeQuery(query, "getDeclinedUserChecksheets");
            nativeQuery.setParameter("userId", userId);
            List<UserChecksheetDTO> result = (List<UserChecksheetDTO>) nativeQuery.getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<Long> getEscalateToUserCheckList(Long checksheetId, Long escalationDays) {
        String query = "SELECT uc.id FROM inspections uc WHERE uc.checksheet_id = " + checksheetId + " and uc.status in ('APPROVED') order by uc.id desc " +
                " limit " + escalationDays + " ";
        List<Long> result = (List<Long>) entityManager.createNativeQuery(query).getResultList();
        return result;
    }
}
