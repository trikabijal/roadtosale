package com.checkSheet.constant;

import lombok.Getter;

@Getter
public enum UserChecksheetStatusType {
    ASSIGNED("Assigned"),       // V1.28: pre-start state for kind=AUDIT|INTERVENTION
    IN_PROGRESS("In Progress"), // --> OPERATOR
    SUBMITTED("Submitted"),     // --> VALIDATOR
    VALIDATED("Validated"),     // --> APPROVER
    APPROVED("Approved"),
    DECLINED("Declined"),       // V1.28: terminal-decline (validator OR approver path); lineage on history table
    // Legacy stored statuses (pre-V1.28). No longer written to inspection.status,
    // but kept for read-side back-compat with rows on older databases that may
    // still surface during phased rollouts. The validator's *input* DTO still
    // uses INVALIDATED to represent the action being taken — see
    // ChecksheetDataValidationStatusType — the stored status maps to DECLINED.
    INVALIDATED("Invalidated"),
    NOT_APPROVED("Not Approved");

    public final String text;
    public final String value;

    UserChecksheetStatusType(String text) {
        this.value = this.name();
        this.text = text;
    }

    public static String getEnumByString(String code){
        for(ChecksheetStatusType e : ChecksheetStatusType.values()){
            if(e.value.equals(code)) return e.getText();
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
