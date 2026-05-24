package com.checkSheet.constant;

import lombok.Getter;

@Getter
public enum ChksQuestionResultType {
    OBJECTIVE("Objective"),
    SUBJECTIVE("Subjective"),
    SUBJECTIVE_CONDITION("Subjective condition"),
    SELECTIVE("Selective"),
    MATRIX("Matrix"),
    NA("Not Applicable"),
    FILE_UPLOAD("File Upload");
    public final String text;
    public final String value;

    ChksQuestionResultType(String text) {
        this.value = this.name();
        this.text = text;
    }

    public static String getEnumByString(String code){
        for(ChksQuestionResultType e : ChksQuestionResultType.values()){
            if(e.value.equals(code)) return e.getText();
        }
        return null;
    }

    @Override
    public String toString() {
        return value;
    }
}