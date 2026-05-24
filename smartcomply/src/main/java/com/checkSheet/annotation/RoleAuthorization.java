package com.checkSheet.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Role-based authorization annotation.
 * Now uses String array for role codes since roles are dynamic from database.
 * Only SUPER_ADMIN is a system constant, other roles are checked against DB.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RoleAuthorization {
    String[] allowedRoles();
}