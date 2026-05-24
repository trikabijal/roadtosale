package com.checkSheet.constant;

/**
 * System role constants.
 * Only SUPER_ADMIN is hardcoded as it has special system-level privileges.
 * All other roles are managed dynamically from the database.
 */
public final class SystemRole {
    
    /**
     * Super Admin role code - has full system access and bypass permissions.
     * This is the only hardcoded role in the system for administration purpose.
     */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    
    private SystemRole() {
        // Prevent instantiation
    }
}