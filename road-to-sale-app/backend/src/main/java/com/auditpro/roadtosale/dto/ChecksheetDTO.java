package com.auditpro.roadtosale.dto;

import java.util.List;

/** Static NADA checksheet, loaded from resources/checksheets/{code}.json. */
public record ChecksheetDTO(
        String code,
        String name,
        String version,
        List<ChecksheetStep> steps) {

    public record ChecksheetStep(
            String id,
            String name,
            int orderNo,
            List<ChecksheetQuestion> questions) {
    }

    public record ChecksheetQuestion(
            String id,
            String text,
            int orderNo,
            boolean isMandatory,
            String resultType) {
    }
}
