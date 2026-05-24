package com.checkSheet.constant;

import java.util.List;

public final class UserDropdownPermissionGroups {

    private UserDropdownPermissionGroups() {
    }

    public static final List<String> CHECKSHEET_PREPARER = List.of(
            "CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE", "CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT"
    );

    public static final List<String> CHECKSHEET_VALIDATOR = List.of(
            "CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_CREATE", "CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_EDIT"
    );

    public static final List<String> CHECKSHEET_APPROVER = List.of(
            "CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_CREATE", "CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_EDIT"
    );

    public static final List<String> DATA_VALIDATOR = List.of(
            "CHECKSHEET_DATA_VALIDATOR_COMMENT_CREATE",
            "CHECKSHEET_DATA_VALIDATOR_COMMENT_EDIT"
    );

    public static final List<String> DATA_APPROVER = List.of(
            "CHECKSHEET_DATA_APPROVER_COMMENT_CREATE", "CHECKSHEET_DATA_APPROVER_COMMENT_EDIT"
    );

    public static final List<String> OPERATOR = List.of(
            "CHECKSHEET_FILL_LISTING", "CHECKSHEET_FILL_CHECKSHEET_DETAIL", "CHECKSHEET_FILL_ANSWER"
    );
}
