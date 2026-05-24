package com.checkSheet.DAO;

import com.checkSheet.DTO.RoleDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class RoleDAO {

    @Autowired
    private EntityManager entityManager;

    public List<RoleDTO> getRoles() {
        try {
            String query = "SELECT r.id, r.name, r.role_code FROM roles r where role_code != 'SUPER_ADMIN' ";
            List<RoleDTO> result = (List<RoleDTO>) entityManager.createNativeQuery(query, "getAllRoles").getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<RoleDTO> getRolesByParentRoleId(Long parentRoleId) {
        try {
            String query = "SELECT r.id, r.name, r.role_code FROM roles r WHERE r.parent_role_id = :parentRoleId";
            List<RoleDTO> result = (List<RoleDTO>) entityManager.createNativeQuery(query, "getAllRoles")
                    .setParameter("parentRoleId", parentRoleId)
                    .getResultList();
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public List<RoleDTO> getRolesByParentRoleIdRecursive(Long parentRoleId) {
        try {
            // Use a simpler approach: get all roles and filter in Java
            // This works across all databases
            String query = "SELECT r.id, r.name, r.role_code, r.parent_role_id FROM roles r";
            @SuppressWarnings("unchecked")
            List<Object[]> allRoles = entityManager.createNativeQuery(query).getResultList();
            
            // Build a map of parent to children
            Map<Long, List<RoleDTO>> roleMap = new HashMap<>();
            Map<Long, RoleDTO> allRoleDTOs = new HashMap<>();
            
            for (Object[] row : allRoles) {
                Long id = ((Number) row[0]).longValue();
                String name = (String) row[1];
                String roleCode = (String) row[2];
                Long parentId = row[3] != null ? ((Number) row[3]).longValue() : null;
                
                RoleDTO roleDTO = new RoleDTO(id, name, roleCode);
                allRoleDTOs.put(id, roleDTO);
                
                if (parentId != null) {
                    roleMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(roleDTO);
                }
            }
            
            // Recursively collect all descendants
            List<RoleDTO> result = new ArrayList<>();
            collectDescendants(parentRoleId, roleMap, result);
            
            return result;
        } catch(Exception e) {
            e.printStackTrace();
            // Fallback to direct children only if recursive query fails
            return getRolesByParentRoleId(parentRoleId);
        }
    }
    
    private void collectDescendants(Long parentId, Map<Long, List<RoleDTO>> roleMap, List<RoleDTO> result) {
        List<RoleDTO> children = roleMap.get(parentId);
        if (children != null) {
            for (RoleDTO child : children) {
                result.add(child);
                // Recursively collect grandchildren
                collectDescendants(child.getId(), roleMap, result);
            }
        }
    }

    public List<RoleDTO> getRolesByAllPermissionCodes(List<String> permissionCodes) {
        try {
            if (permissionCodes == null || permissionCodes.isEmpty()) {
                return new ArrayList<>();
            }

            StringBuilder query = new StringBuilder(
                    "SELECT r.id, r.name, r.role_code " +
                            "FROM roles r " +
                            "JOIN role_permissions rp ON rp.role_id = r.id " +
                            "JOIN permissions p ON p.id = rp.permission_id " +
                            "WHERE r.role_code NOT IN ('SUPER_ADMIN') AND r.deleted_at IS NULL AND p.deleted_at IS NULL "
            );

            StringBuilder inClause = new StringBuilder();
            StringBuilder printableInClause = new StringBuilder();
            for (int i = 0; i < permissionCodes.size(); i++) {
                if (i > 0) {
                    inClause.append(", ");
                    printableInClause.append(", ");
                }
                inClause.append(":permissionCode").append(i);
                printableInClause.append("'").append(permissionCodes.get(i).replace("'", "''")).append("'");
            }

            query.append("AND UPPER(TRIM(p.permission_code)) IN (")
                    .append(inClause)
                    .append(") ")
                    .append("GROUP BY r.id, r.name, r.role_code ")
                    .append("HAVING COUNT(DISTINCT UPPER(TRIM(p.permission_code))) = ")
                    .append(permissionCodes.size())
                    .append(" ")
                    .append("ORDER BY r.name ASC");


            String printableSql = "SELECT r.id, r.name, r.role_code FROM roles r JOIN role_permissions rp ON rp.role_id = r.id " +
                    "JOIN permissions p ON p.id = rp.permission_id WHERE r.deleted_at IS NULL AND p.deleted_at IS NULL " +
                    "AND UPPER(TRIM(p.permission_code)) IN (" + printableInClause + ") " +
                    "GROUP BY r.id, r.name, r.role_code " +
                    "HAVING COUNT(DISTINCT UPPER(TRIM(p.permission_code))) = " + permissionCodes.size() + " " +
                    "ORDER BY r.name ASC";
               
            System.out.println("getRolesByAllPermissionCodes SQL => " + printableSql);
            System.out.println("getRolesByAllPermissionCodes params => permissionCodes=" + permissionCodes + ", requiredPermissionCount=" + permissionCodes.size());

            Query nativeQuery = entityManager.createNativeQuery(query.toString(), "getAllRoles");
            for (int i = 0; i < permissionCodes.size(); i++) {
                nativeQuery.setParameter("permissionCode" + i, permissionCodes.get(i));
            }

            return (List<RoleDTO>) nativeQuery.getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
