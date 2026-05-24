package com.checkSheet.constant;

import lombok.Getter;

@Getter
public enum ChecksheetApprovalStatusType {
    APPROVED("Approved"),
    NOT_APPROVED("Not approved");

    public final String text;
    public final String value;

    ChecksheetApprovalStatusType(String text) {
        this.value = this.name();
        this.text = text;
    }

    public static String getEnumByString(String code){
        for(ChecksheetApprovalStatusType e : ChecksheetApprovalStatusType.values()){
            if(e.value.equals(code)) return e.getText();
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}