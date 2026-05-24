package com.checkSheet.constant;

import lombok.Getter;

@Getter
public enum ChecksheetType {
    PUBLIC("Public"),
    PRIVATE("Private");

    public final String text;
    public final String value;

    ChecksheetType(String text) {
        this.value = this.name();
        this.text = text;
    }

    public static String getEnumByString(String code){
        for(ChecksheetType e : ChecksheetType.values()){
            if(e.value.equals(code)) return e.getText();
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}
