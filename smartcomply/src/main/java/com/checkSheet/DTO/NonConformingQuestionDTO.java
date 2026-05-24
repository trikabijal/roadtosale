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
public class NonConformingQuestionDTO {
    
    // Request fields
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    
    private List<Long> checksheetIds;
    private Integer limit;
    
    // Response fields
    private Long questionId;
    private Long checksheetId;
    private String checksheetName;
    private String questionName;
    private String questionDescription;
    private Long totalCount;
    private Long notOkCount;
    private Double notOkPercentage;
    private String trend; // UP, DOWN, STABLE
    private Double previousPeriodPercentage;
    
    // Constructor for SQL result mapping
    public NonConformingQuestionDTO(Long questionId, Long checksheetId, String checksheetName,
                                   String questionName, String questionDescription,
                                   Long totalCount, Long notOkCount) {
        this.questionId = questionId;
        this.checksheetId = checksheetId;
        this.checksheetName = checksheetName;
        this.questionName = questionName;
        this.questionDescription = questionDescription;
        this.totalCount = totalCount != null ? totalCount : 0L;
        this.notOkCount = notOkCount != null ? notOkCount : 0L;
        this.notOkPercentage = totalCount != null && totalCount > 0 
            ? (notOkCount.doubleValue() / totalCount.doubleValue()) * 100.0 
            : 0.0;
    }
}
