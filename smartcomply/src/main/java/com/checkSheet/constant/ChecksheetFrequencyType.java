package com.checkSheet.constant;

import lombok.Getter;

@Getter
public enum ChecksheetFrequencyType {
    SHIFT("Shift"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    UNPLANNED("No Planning");

    public final String text;
    public final String value;

    ChecksheetFrequencyType(String text) {
        this.value = this.name();
        this.text = text;
    }

    public static String getEnumByString(String code){
        for(ChecksheetFrequencyType e : ChecksheetFrequencyType.values()){
            if(e.value.equals(code)) return e.getText();
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}