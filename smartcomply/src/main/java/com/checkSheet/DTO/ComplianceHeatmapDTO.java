package com.checkSheet.DTO;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComplianceHeatmapDTO {
    
    // Request fields
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    
    private Integer departmentLevel;
    private String groupBy; // WEEK, MONTH
    
    // Response fields
    private Long departmentId;
    private String departmentName;
    private List<PeriodData> periods;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeriodData {
        private String period; // e.g., "2024-01" for month, "2024-W01" for week
        private Double complianceScore;
        private Long totalChecksheets;
        private Long okCount;
        private Long notOkCount;
    }
}
