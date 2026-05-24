package com.checkSheet.constant;

/**
 * Permission codes for the Intervention feature.
 * Codes match V1.27 seed rows + V100.003 grants.
 */
public final class InterventionPermissions {
    public static final String INTERVENTION_MANAGE                    = "INTERVENTION_MANAGE";
    public static final String INTERVENTION_ASSIGNMENT_VIEW           = "INTERVENTION_ASSIGNMENT_VIEW";
    public static final String INTERVENTION_ASSIGNMENT_MANAGE         = "INTERVENTION_ASSIGNMENT_MANAGE";
    public static final String INTERVENTION_ASSIGNMENT_ACKNOWLEDGE    = "INTERVENTION_ASSIGNMENT_ACKNOWLEDGE";
    public static final String INTERVENTION_CONDUCT                   = "INTERVENTION_CONDUCT";

    private InterventionPermissions() {}
}
