package com.checkSheet.constant;

import lombok.Getter;

@Getter
public enum ChksQuestionResultObjectiveType {
    RANGE("Range"),
    EQUAL_TO("Equal to"),
    LESS_THAN("Less than"),
    LESS_THAN_OR_EQUAL_TO("Less than than OR Equal to"),
    GREATER_THAN("Greater than"),
    GREATER_THAN_OR_EQUAL_TO("Greater than OR Equal to");

    public final String text;
    public final String value;

    ChksQuestionResultObjectiveType(String text) {
        this.value = this.name();
        this.text = text;
    }

    public static String getEnumByString(String code){
        for(ChksQuestionResultObjectiveType e : ChksQuestionResultObjectiveType.values()){
            if(e.value.equals(code)) return e.getText();
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}