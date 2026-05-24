package com.checkSheet.DAO;

import com.checkSheet.DTO.DepartmentDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
public class DepartmentDAO {

    @Autowired
    private EntityManager entityManager;

    public List<DepartmentDTO> getAllDepartments(Long departmentId) {
        try {
            String query = "select d.id, d.name, urd.user_id, urd.first_name, urd.last_name, urd.email, urd.username, d.created_at, d.department_id from " +
                    "  (select * from departments d where 1=1 " ;
            if (!Objects.equals(departmentId, null)) {
                query += " and d.id = '" + departmentId + "' ";
            }
            query += " ) d join \n" +
                    " (select r.id as role_id, r.name as role_name, u.id as user_id, u.first_name as first_name, u.last_name as last_name, urd.department_id, \n" +
                    " u.email, u.username, r.role_code \n" +
                    " from user_role_departments urd join users u on u.id=urd.user_id \n" +
                    " join " +
                    " (select * from roles r ";
            if (Objects.equals(departmentId, null)) {
                query += " where r.role_code = 'DEPT_ADMIN'";
            }


            query += " ) r on r.id = urd.role_id) urd on urd.department_id = d.id  ";
            List<DepartmentDTO> result = (List<DepartmentDTO>) entityManager.createNativeQuery(query, "getAllDepartments").getResultList();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<DepartmentDTO> getSectionHead(Long departmentId) {
        try {
            String query = "select d.id, d.name, urd.user_id, urd.first_name, urd.last_name, urd.email, urd.username, d.created_at, d.department_id from " +
                "  (select * from departments d where d.id = '" + departmentId + "') d join \n" +
                " (select r.id as role_id, r.name as role_name, u.id as user_id, u.first_name as first_name, u.last_name as last_name, urd.department_id, u.email, u.username, r.role_code \n" +
                " from user_role_departments urd join users u on u.id=urd.user_id \n" +
                " join " +
                " (select * from roles r where r.role_code = 'SUBDEPT_ADMIN') r on r.id = urd.role_id) urd on urd.department_id = d.id  ";
            List<DepartmentDTO> result = (List<DepartmentDTO>) entityManager.createNativeQuery(query, "getAllDepartments").getResultList();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public Page<DepartmentDTO> searchDepartments(DepartmentDTO departmentDTO, String serach) {
        try {
            String countQuery = "";
            String actualQuery = "";
            String nativeQuery = "";
            actualQuery = "select d.id, d.name, urd.user_id, urd.first_name, urd.last_name, urd.email, urd.username, urd.role_id, urd.role_name, urd.role_code, " +
                    " d.created_at, urd.id as urd_id,pd.name as dept_name ";
            countQuery = "select count(d.id) ";

            nativeQuery += " from " +
                    " (select * from departments d where 1=1 ";
            /*
            if(!Objects.equals(departmentDTO.getDepartmentId(), null)) {
                nativeQuery += " and d.department_id = '" + departmentDTO.getDepartmentId() + "' ";
            }
            if(!Objects.equals(departmentDTO.getId(), null)) {
                nativeQuery += " and (d.department_id = '" + departmentDTO.getId() + "' or d.id = '" + departmentDTO.getId() + "' ) ";
            }
            if(!Objects.equals(departmentDTO.getIsSubDepartment(), null) && departmentDTO.getIsSubDepartment()) {
                nativeQuery += " and d.department_id is not null ";
            }
            */
            String csvDepartmentIds ="";

            if(departmentDTO.getDepartmentIds() != null && !departmentDTO.getDepartmentIds().isEmpty()){
                csvDepartmentIds = departmentDTO.getDepartmentIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                nativeQuery += " and d.id in("+csvDepartmentIds+")";
            }
            nativeQuery += " ) d left join departments pd on pd.id = d.department_id join \n" +
                    " (select r.id as role_id, r.name as role_name, u.id as user_id, u.first_name as first_name, u.last_name as last_name, urd.department_id, \n" +
                    " u.email, u.username, r.role_code,urd.id \n" +
                    " from (select * from user_role_departments urd where 1=1 and urd.deleted_by is null ";
            /*
            if(!Objects.equals(departmentDTO.getId(), null) && Objects.equals(serach, "user")) {
                nativeQuery += " and urd.department_id = '" + departmentDTO.getId() + "' ";
            }

             */
            if(!csvDepartmentIds.isBlank()){
                nativeQuery += " and urd.department_id in (" + csvDepartmentIds + ") ";
            }
            nativeQuery += " ) urd join users u on u.id=urd.user_id \n" +
                    " join " +
                    " (select * from roles r where 1=1 ";
            if (!Objects.equals(departmentDTO.getRoleId(), null)) {
                nativeQuery += " and r.id = '" + departmentDTO.getRoleId() + "' ";
            }
            if (!Objects.equals(serach, null) && Objects.equals(serach, "department")) {
                nativeQuery += " and r.role_code = 'DEPT_ADMIN' ";
            } else if (!Objects.equals(serach, null) && Objects.equals(serach, "section")) {
                nativeQuery += " and r.role_code = 'SUBDEPT_ADMIN' ";
            }
            nativeQuery += " ) r on r.id = urd.role_id) urd on urd.department_id = d.id where 1=1 ";
            if(!Objects.equals(departmentDTO.getSearch(), null) && !departmentDTO.getSearch().trim().isEmpty()) {
                nativeQuery += " and ( lower(d.name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.first_name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.last_name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.email) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.username) like '%" + departmentDTO.getSearch().toLowerCase() + "%' \n" +
                        " ) ";
            }
            countQuery += nativeQuery;
            nativeQuery += "   ORDER BY d.id, urd.user_id, urd.role_id DESC " +
                    " limit " + departmentDTO.getPerPageRecord() + " OFFSET " + departmentDTO.getCurrentPage() * departmentDTO.getPerPageRecord() + " ";
            actualQuery += nativeQuery;
            Pageable pageable = PageRequest.of(Math.toIntExact(departmentDTO.getCurrentPage()), Math.toIntExact(departmentDTO.getPerPageRecord()));
            Query getActiveLeadFollowup = entityManager.createNativeQuery(actualQuery, "searchDepartments");

            List<DepartmentDTO> resultList = (List<DepartmentDTO>) getActiveLeadFollowup.getResultList();
            Long totalCount = countQuery(countQuery, departmentDTO);
            return new PageImpl<>(resultList, pageable, totalCount);
        } catch (Exception e) {
            e.printStackTrace();
            Pageable pageable = PageRequest.of(Math.toIntExact(departmentDTO.getCurrentPage()), Math.toIntExact(departmentDTO.getPerPageRecord()));
            return new PageImpl<>(new ArrayList<>(), pageable, 0L);
        }
    }

    public Page<DepartmentDTO> searchUsers(DepartmentDTO departmentDTO, String serach) {
        return searchUsers(departmentDTO, serach, null);
    }

    // Overloaded version with optional roleCodes argument
    public Page<DepartmentDTO> searchUsers(DepartmentDTO departmentDTO, String serach, List<String> roleCodes) {
        try {
            String countQuery = "";
            String actualQuery = "";
            String nativeQuery = "";
            
            // New Select Clause for Aggregation
            actualQuery = "select urd.user_id, urd.first_name, urd.last_name, urd.email, urd.username, " +
                    " string_agg(distinct cast(urd.role_id as text), ',') as role_ids_str, " +
                    " string_agg(distinct urd.role_name, ',') as role_name, " +
                    " string_agg(distinct urd.role_code, ',') as role_code, " +
                    " string_agg(distinct cast(case when d.department_id is null then d.id else null end as text), ',') as dept_ids_str, " +
                    " string_agg(distinct case when d.department_id is null then d.name else null end, ',') as dept_name, " +
                    " string_agg(distinct cast(case when d.department_id is not null then d.id else null end as text), ',') as sect_ids_str, " +
                    " string_agg(distinct case when d.department_id is not null then d.name else null end, ',') as sect_name ";

            countQuery = "select count(distinct urd.user_id) ";

            nativeQuery += " from " +
                    " (select * from departments d where 1=1 ";
           
            /* -- New implementation -- start */
            String csvDepartmentIds ="";

            if(departmentDTO.getDepartmentIds() != null && !departmentDTO.getDepartmentIds().isEmpty()){
                csvDepartmentIds = departmentDTO.getDepartmentIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                nativeQuery += " and d.id in("+csvDepartmentIds+")";
            }
            String chksQry = "",deptQry = "";
            if(departmentDTO.getChecksheetIds() != null && !departmentDTO.getChecksheetIds().isEmpty()){
                String chksIds = departmentDTO.getChecksheetIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                chksQry = " join checksheets c on c.department_id = d.id and c.id in ("+chksIds+") ";
                deptQry = " and urd.user_id = ANY(c.operator_user_ids) ";
            }
            nativeQuery += " ) d "+chksQry+" join \n" +
                    " (select r.id as role_id, r.name as role_name, u.id as user_id, u.first_name as first_name, u.last_name as last_name, urd.department_id, \n" +
                    " u.email, u.username, r.role_code,urd.id as urd_id  \n" +
                    " from (select * from user_role_departments urd where 1=1 and urd.deleted_by is null ";

            if(!csvDepartmentIds.isBlank()){
                nativeQuery += " and urd.department_id in (" + csvDepartmentIds + ") ";
            }
            String csvRoleIds = "";
            if(departmentDTO.getRoleIds() != null && !departmentDTO.getRoleIds().isEmpty()){
                csvRoleIds = departmentDTO.getRoleIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                nativeQuery += " and urd.role_id in (" + csvRoleIds + ") ";
            }

            // Check if SUBDEPT_ADMIN exist in this roleCodes variable.
            boolean hasSubdeptAdmin = false;
            String ignoreRoles = "";
            if (roleCodes != null && !roleCodes.isEmpty()) {
                if(roleCodes.contains("SUBDEPT_ADMIN")){
                    ignoreRoles = "'SUPER_ADMIN','DEPT_ADMIN'"; 
                }else if(roleCodes.contains("DEPT_ADMIN")){
                    ignoreRoles = "'SUPER_ADMIN'"; 
                }
            }
            nativeQuery += " ) urd join users u on u.id=urd.user_id\n" +
                    " join " +
                    " (select * from roles r where 1=1 "
                    + (ignoreRoles != null && !ignoreRoles.isEmpty()
                        ? " and r.role_code NOT IN (" +ignoreRoles + ") "
                        : " ");

            if(!csvRoleIds.isBlank()){
                nativeQuery += " and r.id in (" + csvRoleIds + ") ";
            }
            nativeQuery += " ) r on r.id = urd.role_id) urd on urd.department_id = d.id "+deptQry+" where 1=1 ";
            if(!Objects.equals(departmentDTO.getSearch(), null) && !departmentDTO.getSearch().trim().isEmpty()) {
                nativeQuery += " and ( lower(urd.first_name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.last_name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.email) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.username) like '%" + departmentDTO.getSearch().toLowerCase() + "%' \n" +
                        " ) ";
            }
            countQuery += nativeQuery;
            
            // Add Group By
            nativeQuery += " GROUP BY urd.user_id, urd.first_name, urd.last_name, urd.email, urd.username ";
            
            nativeQuery += " ORDER BY urd.user_id DESC " +
                    " limit " + departmentDTO.getPerPageRecord() + " OFFSET " + departmentDTO.getCurrentPage() * departmentDTO.getPerPageRecord() + " ";
            actualQuery += nativeQuery;
            Pageable pageable = PageRequest.of(Math.toIntExact(departmentDTO.getCurrentPage()), Math.toIntExact(departmentDTO.getPerPageRecord()));
            
            // Use the new mapping
            Query getActiveLeadFollowup = entityManager.createNativeQuery(actualQuery, "searchUniqueUsers");

            List<DepartmentDTO> resultList = (List<DepartmentDTO>) getActiveLeadFollowup.getResultList();
            Long totalCount = countQuery(countQuery, departmentDTO);
            System.out.println("Final SQL Query:");
            System.out.println(actualQuery);
            return new PageImpl<>(resultList, pageable, totalCount);
        } catch (Exception e) {
            e.printStackTrace();
            Pageable pageable = PageRequest.of(Math.toIntExact(departmentDTO.getCurrentPage()), Math.toIntExact(departmentDTO.getPerPageRecord()));
            return new PageImpl<>(new ArrayList<>(), pageable, 0L);
        }
    }

    public List<DepartmentDTO> searchDepartmentsForDownload(DepartmentDTO departmentDTO, String serach) {
        try {
            String actualQuery = "";
            actualQuery = "select d.id, d.name, urd.user_id, urd.first_name, urd.last_name, urd.email, urd.username, urd.role_id, urd.role_name, urd.role_code from " +
                    " (select * from departments d where 1=1 ";
            /*
            if(!Objects.equals(departmentDTO.getDepartmentId(), null)) {
                actualQuery += " and d.department_id = '" + departmentDTO.getDepartmentId() + "' ";
            }
            if(!Objects.equals(departmentDTO.getId(), null)) {
                actualQuery += " and (d.department_id = '" + departmentDTO.getId() + "' or d.id = '" + departmentDTO.getId() + "' ) ";
            }
            if(!Objects.equals(departmentDTO.getIsSubDepartment(), null) && departmentDTO.getIsSubDepartment()) {
                actualQuery += " and d.department_id is not null ";
            }
            */
            String csvDepartmentIds ="";

            if(departmentDTO.getDepartmentIds() != null && !departmentDTO.getDepartmentIds().isEmpty()){
                csvDepartmentIds = departmentDTO.getDepartmentIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                actualQuery += " and d.id in("+csvDepartmentIds+")";
            }
            actualQuery += " ) d join \n" +
                    " (select r.id as role_id, r.name as role_name, u.id as user_id, u.first_name as first_name, u.last_name as last_name, urd.department_id, \n" +
                    " u.email, u.username, r.role_code \n" +
                    " from (select * from user_role_departments urd where 1=1 and urd.deleted_by is null ";
            /*
            if(!Objects.equals(departmentDTO.getId(), null) && Objects.equals(serach, "user")) {
                actualQuery += " and urd.department_id = '" + departmentDTO.getId() + "' ";
            }*/
            if(!csvDepartmentIds.isBlank()){
                actualQuery += " and urd.department_id in (" + csvDepartmentIds + ") ";
            }
            actualQuery += " ) urd join users u on u.id=urd.user_id \n" +
                    " join " +
                    " (select * from roles r where 1=1 ";
            if (!Objects.equals(departmentDTO.getRoleId(), null)) {
                actualQuery += " and r.id = '" + departmentDTO.getRoleId() + "' ";
            }
            if (!Objects.equals(serach, null) && Objects.equals(serach, "department")) {
                actualQuery += " and r.role_code = 'DEPT_ADMIN' ";
            } else if (!Objects.equals(serach, null) && Objects.equals(serach, "section")) {
                actualQuery += " and r.role_code = 'SUBDEPT_ADMIN' ";
            }
            actualQuery += " ) r on r.id = urd.role_id) urd on urd.department_id = d.id where 1=1 ";
            if(!Objects.equals(departmentDTO.getSearch(), null) && !departmentDTO.getSearch().trim().isEmpty()) {
                actualQuery += " and ( lower(d.name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.first_name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.last_name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.email) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.username) like '%" + departmentDTO.getSearch().toLowerCase() + "%' \n" +
                        " ) ";
            }
            actualQuery += "   ORDER BY d.id, urd.user_id, urd.role_id DESC ";
            Query getActiveLeadFollowup = entityManager.createNativeQuery(actualQuery, "searchDepartmentsForDownload");

            List<DepartmentDTO> resultList = (List<DepartmentDTO>) getActiveLeadFollowup.getResultList();
            return resultList;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }



    public List<DepartmentDTO> searchUsersForDownload(DepartmentDTO departmentDTO, String serach) {
        try {
            String actualQuery = "";
            actualQuery = "select d.id, d.name, urd.user_id, urd.first_name, urd.last_name, urd.email, urd.username, urd.role_id, urd.role_name, urd.role_code from " +
                    " (select * from departments d where 1=1 ";
            /*
            if(!Objects.equals(departmentDTO.getDepartmentId(), null)) {
                actualQuery += " and d.department_id = '" + departmentDTO.getDepartmentId() + "' ";
            }
            if(!Objects.equals(departmentDTO.getId(), null)) {
                actualQuery += " and (d.department_id = '" + departmentDTO.getId() + "' or d.id = '" + departmentDTO.getId() + "' ) ";
            }
            if(!Objects.equals(departmentDTO.getIsSubDepartment(), null) && departmentDTO.getIsSubDepartment()) {
                actualQuery += " and d.department_id is not null ";
            }
            */
            String csvDepartmentIds ="";

            if(departmentDTO.getDepartmentIds() != null && !departmentDTO.getDepartmentIds().isEmpty()){
                csvDepartmentIds = departmentDTO.getDepartmentIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                actualQuery += " and d.id in("+csvDepartmentIds+")";
            }
            String chksQry = "";
            if(departmentDTO.getChecksheetIds() != null && !departmentDTO.getChecksheetIds().isEmpty()){
                String chksIds = departmentDTO.getChecksheetIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                chksQry = " join checksheets c on c.department_id = d.id and c.id in ("+chksIds+") ";
            }
            actualQuery += " ) d "+chksQry+" join \n" +
                    " (select r.id as role_id, r.name as role_name, u.id as user_id, u.first_name as first_name, u.last_name as last_name, urd.department_id, \n" +
                    " u.email, u.username, r.role_code \n" +
                    " from (select * from user_role_departments urd where 1=1 and urd.deleted_by is null ";
            /*
            if(!Objects.equals(departmentDTO.getId(), null) && Objects.equals(serach, "user")) {
                actualQuery += " and urd.department_id = '" + departmentDTO.getId() + "' ";
            }*/
            if(!csvDepartmentIds.isBlank()){
                actualQuery += " and urd.department_id in (" + csvDepartmentIds + ") ";
            }
            String csvRoleIds = "";
            if(departmentDTO.getRoleIds() != null && !departmentDTO.getRoleIds().isEmpty()){
                csvRoleIds = departmentDTO.getRoleIds().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
                actualQuery += " and urd.role_id in (" + csvRoleIds + ") ";
            }
            actualQuery += " ) urd join users u on u.id=urd.user_id \n" +
                    " join " +
                    " (select * from roles r where 1=1 ";
//            if (!Objects.equals(departmentDTO.getRoleId(), null)) {
//                actualQuery += " and r.id = '" + departmentDTO.getRoleId() + "' ";
//            }
            if(!csvRoleIds.isBlank()){
                actualQuery += " and r.id in (" + csvRoleIds + ") ";
            }

            actualQuery += " ) r on r.id = urd.role_id) urd on urd.department_id = d.id where 1=1 ";
            if(!Objects.equals(departmentDTO.getSearch(), null) && !departmentDTO.getSearch().trim().isEmpty()) {
                actualQuery += " and ( lower(urd.first_name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.last_name) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.email) like '%" + departmentDTO.getSearch().toLowerCase() + "%' or \n" +
                        " lower(urd.username) like '%" + departmentDTO.getSearch().toLowerCase() + "%' \n" +
                        " ) ";
            }
            actualQuery += "   ORDER BY d.id, urd.user_id, urd.role_id DESC ";
            Query getActiveLeadFollowup = entityManager.createNativeQuery(actualQuery, "searchDepartmentsForDownload");

            List<DepartmentDTO> resultList = (List<DepartmentDTO>) getActiveLeadFollowup.getResultList();
            return resultList;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }


    public Long countQuery(String query, DepartmentDTO departmentDTO) {
        Query nativeQuery = entityManager.createNativeQuery(query);
        Long result = (Long) nativeQuery
                .getSingleResult();
        return result;
    }

    public List<DepartmentDTO> getDepartments() {
        try {
            String query = """
                SELECT 
                    d.id, 
                    d.department_id, 
                    d.name 
                FROM departments d 
                WHERE d.deleted_at IS NULL and d.department_id is null
                """;

            List<Object[]> results = entityManager.createNativeQuery(query).getResultList();

            List<DepartmentDTO> departments = new ArrayList<>();
            for (Object[] result : results) {
                DepartmentDTO dto = new DepartmentDTO();
                dto.setId(result[0] != null ? Long.valueOf(result[0].toString()) : null);
                dto.setDepartmentId(result[1] != null ? Long.valueOf(result[1].toString()) : null);
                dto.setName(result[2] != null ? result[2].toString() : null);
                departments.add(dto);
            }
            return departments;
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
