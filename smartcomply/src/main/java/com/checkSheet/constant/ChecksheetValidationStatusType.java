package com.checkSheet.constant;

import lombok.Getter;

@Getter
public enum ChecksheetValidationStatusType {
    INVALIDATED("Invalidated"),
    VALIDATED("Validated");

    public final String text;
    public final String value;

    ChecksheetValidationStatusType(String text) {
        this.value = this.name();
        this.text = text;
    }

    public static String getEnumByString(String code){
        for(ChecksheetValidationStatusType e : ChecksheetValidationStatusType.values()){
            if(e.value.equals(code)) return e.getText();
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}