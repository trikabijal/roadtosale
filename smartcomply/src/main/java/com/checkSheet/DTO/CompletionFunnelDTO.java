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
public class CompletionFunnelDTO {
    
    // Request fields
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    
    private List<Long> departmentIds;
    private String frequencyOfCheck;
    
    // Response fields
    private Long planned;
    private Long inProgress;
    private Long submitted;
    private Long validated;
    private Long approved;
    private Long rejected;
    private List<StatusBreakdown> breakdown;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusBreakdown {
        private String status;
        private Long count;
        private Double percentage;
    }
}
