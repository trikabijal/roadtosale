package com.checkSheet.constant;

import lombok.Getter;

@Getter
public enum ChecksheetStatusType {
    NEW("New"),
    CREATE_TEMPLATE("Create template"),
    CREATE_CONTENT("Create content"),
    SUBMITTED_FOR_VALIDATE("Submitted for validate"),
    INVALIDATED("Invalidated"),
    VALIDATED("Validated"),
    APPROVED("Approved"),
    NOT_APPROVED("Not approved");

    public final String text;
    public final String value;

    ChecksheetStatusType(String text) {
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