package com.checkSheet.DTO;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PlanVsActualDTO {
    private Long checksheetId;
    private String checksheetName;
    private Integer planned;
    private Integer completed;
    private Integer inProgress;
    private Integer missed;
    private Integer inComplete;
    private Integer npd;
    private List<DayData> chartData;

    @Data
    @NoArgsConstructor
    public static class DayData {
        private String date;
        private String status;
        private String remarks;
        private List<ShiftData> shiftData;
    }

    @Data
    @NoArgsConstructor
    public static class ShiftData {
        private String shift;
        private String status;
        private String remarks;
    }
} 