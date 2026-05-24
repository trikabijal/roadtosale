package com.checkSheet.DAO;

import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.UserDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Repository
public class UserDAO {

    private static final Logger log = LoggerFactory.getLogger(UserDAO.class);

    @Autowired
    private EntityManager entityManager;

    public List<Long> getUserByEmailAndUserId(String email, Long userId) {
        try {
            String query = "SELECT u.id FROM users u " +
                    " WHERE u.email  = '" + email + "' and u.id != '" + userId + "' ";
            List<Long> result = (List<Long>) entityManager.createNativeQuery(query).getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public UserDTO getUserById(UserDTO userDTO) {
        try {
            String query = "SELECT u.id, u.first_name, u.last_name, u.email, u.username, u.mobile FROM users u " +
                    " WHERE u.id = '" + userDTO.getId() + "' ";
            UserDTO result = (UserDTO) entityManager.createNativeQuery(query, "getUserById").getSingleResult();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new UserDTO();
        }
    }

    public List<UserDTO> getAllOperators(UserDTO userDTO) {
        try {
            List<Long> departmentIds = userDTO.getDepartmentIds();
            String query = "SELECT u.id, u.first_name, u.last_name, u.email, u.username, u.mobile FROM users u " +
                    " join (select * from user_role_departments urd where 1=1 ";
            if(!Objects.isNull(userDTO.getRoleId())) {
                query += " and urd.role_id = '" + userDTO.getRoleId() + "' ";
            }
            if (departmentIds != null && !departmentIds.isEmpty()) {
                query += " and urd.department_id in( :departmentIds ) ";
            }
            query += " ) urd on urd.user_id = u.id order by u.id desc ";
            Query nativeQuery = entityManager.createNativeQuery(query, "getUserById");

            if (departmentIds != null && !departmentIds.isEmpty()) {
                nativeQuery.setParameter("departmentIds", departmentIds);
            }

            List<UserDTO> result = (List<UserDTO>) nativeQuery.getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public Page<UserDTO> getUsersByPermission(UserDTO userDTO, List<String> permissionCodes, Pageable pageable, List<Long> allowedDepartmentIds) {
        try {
            if (allowedDepartmentIds != null && allowedDepartmentIds.isEmpty()) {
                return new PageImpl<>(new ArrayList<>(), pageable, 0);
            }

            Set<Long> requestedDepartmentIds = new HashSet<>();
            if (userDTO.getDepartmentId() != null) {
                requestedDepartmentIds.add(userDTO.getDepartmentId());
            }
            if (userDTO.getDepartmentIds() != null) {
                requestedDepartmentIds.addAll(userDTO.getDepartmentIds());
            }

            Set<Long> requestedSectionIds = new HashSet<>();
            if (userDTO.getSectionIds() != null) {
                requestedSectionIds.addAll(userDTO.getSectionIds());
            }

            StringBuilder fromClause = new StringBuilder()
                    .append(" FROM users u ")
                    .append(" JOIN user_role_departments urd ON urd.user_id = u.id ")
                    .append(" AND urd.deleted_at IS NULL AND urd.deleted_by IS NULL ")
                    .append(" JOIN departments d ON d.id = urd.department_id ")
                    .append(" AND d.deleted_at IS NULL ");

            if (permissionCodes != null && !permissionCodes.isEmpty()) {
                fromClause.append(" JOIN role_permissions rp ON rp.role_id = urd.role_id ")
                        .append(" JOIN permissions p ON p.id = rp.permission_id ")
                        .append(" AND p.deleted_at IS NULL ");
            }

            StringBuilder whereClause = new StringBuilder(" WHERE u.deleted_at IS NULL ");

            if (permissionCodes != null && !permissionCodes.isEmpty()) {
                whereClause.append(" AND p.permission_code IN (:permissionCodes) ");
            }

            if (!requestedDepartmentIds.isEmpty()) {
                whereClause.append(" AND (d.id IN (:requestedDepartmentIds) OR d.department_id IN (:requestedDepartmentIds)) ");
            }

            if (!requestedSectionIds.isEmpty()) {
                whereClause.append(" AND d.id IN (:requestedSectionIds) ");
            }

            if (allowedDepartmentIds != null) {
                whereClause.append(" AND (d.id IN (:allowedDepartmentIds) OR d.department_id IN (:allowedDepartmentIds)) ");
            }

            if (userDTO.getSearch() != null && !userDTO.getSearch().trim().isEmpty()) {
                whereClause.append(" AND (")
                        .append(" LOWER(COALESCE(u.first_name, '')) LIKE :search ")
                        .append(" OR LOWER(COALESCE(u.last_name, '')) LIKE :search ")
                        .append(" OR LOWER(COALESCE(u.username, '')) LIKE :search ")
                        .append(" OR LOWER(COALESCE(u.email, '')) LIKE :search ")
                        .append(" OR LOWER(TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, ''))) LIKE :search ")
                        .append(") ");
            }

            String selectQuery = "SELECT user_list.id, user_list.first_name, user_list.last_name, user_list.email, user_list.username, user_list.mobile "
                    + " FROM (SELECT DISTINCT u.id, u.first_name, u.last_name, u.email, u.username, u.mobile "
                    + fromClause + whereClause
                    + " ) user_list "
                    + " ORDER BY user_list.id desc ";

            String countQuery = "SELECT COUNT(DISTINCT u.id) " + fromClause + whereClause;

            log.info("getUsersByPermission selectQuery: {}", selectQuery);
            log.info("getUsersByPermission countQuery: {}", countQuery);
            log.info(
                    "getUsersByPermission params => permissionCodes: {}, requestedDepartmentIds: {}, requestedSectionIds: {}, allowedDepartmentIds: {}, search: {}, page: {}, size: {}",
                    permissionCodes,
                    requestedDepartmentIds,
                    requestedSectionIds,
                    allowedDepartmentIds,
                    userDTO.getSearch(),
                    pageable.getPageNumber(),
                    pageable.getPageSize()
            );

            Query resultQuery = entityManager.createNativeQuery(selectQuery, "getUserById");
            Query totalQuery = entityManager.createNativeQuery(countQuery);

            if (permissionCodes != null && !permissionCodes.isEmpty()) {
                resultQuery.setParameter("permissionCodes", permissionCodes);
                totalQuery.setParameter("permissionCodes", permissionCodes);
            }
            if (!requestedDepartmentIds.isEmpty()) {
                resultQuery.setParameter("requestedDepartmentIds", requestedDepartmentIds);
                totalQuery.setParameter("requestedDepartmentIds", requestedDepartmentIds);
            }
            if (!requestedSectionIds.isEmpty()) {
                resultQuery.setParameter("requestedSectionIds", requestedSectionIds);
                totalQuery.setParameter("requestedSectionIds", requestedSectionIds);
            }
            if (allowedDepartmentIds != null) {
                resultQuery.setParameter("allowedDepartmentIds", allowedDepartmentIds);
                totalQuery.setParameter("allowedDepartmentIds", allowedDepartmentIds);
            }
            if (userDTO.getSearch() != null && !userDTO.getSearch().trim().isEmpty()) {
                String search = "%" + userDTO.getSearch().trim().toLowerCase() + "%";
                resultQuery.setParameter("search", search);
                totalQuery.setParameter("search", search);
            }

            resultQuery.setFirstResult((int) pageable.getOffset());
            resultQuery.setMaxResults(pageable.getPageSize());

            List<UserDTO> users = resultQuery.getResultList();
            Number total = (Number) totalQuery.getSingleResult();
            return new PageImpl<>(users, pageable, total.longValue());
        } catch(Exception e) {
            e.printStackTrace();
            return new PageImpl<>(new ArrayList<>(), pageable, 0);
        }
    }
}
