package com.checkSheet.DTO;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonFormat;
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
public class DashboardDTO {
    private Long id;
    private String name;
    private List<Long> checksheetIds;
    private Long checksheetHeaderId;
    private Long checksheetQuestionId;
    private Long checksheetQuestionResultId;
    private List<Long> questionIds;
    private List<ParamValDTO> parameters;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;

    private String frequencyOfCheck;
    private Long okCount;
    private Long notOkCount;

    private Long checksheetId;
    private Long chksHeaderDataId;
    private Long chksHeaderId;
    private Long level;
    private Long version;
    private Boolean isDataValidator;
    private Long loginUserId;

    // Constructor for getRespectedQuestions
    public DashboardDTO(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public DashboardDTO(Long id, Long version, String name) {
        this.id = id;
        this.version = version;
        this.name = name;

    }

    // Constructor for getChecksheetSummaryData
    public DashboardDTO(Long id, String name, Long okCount, Long notOkCount) {
        this.id = id;
        this.name = name;
        this.okCount = okCount;
        this.notOkCount = notOkCount;
    }

    // Constructor for getRespectedChecksheetHeaderData query result mapping
    public DashboardDTO(String name, Long id, Long chksHeaderId, Long chksHeaderDataId, Long level) {
        this.id = id;
        this.name = name;
        this.chksHeaderId = chksHeaderId;
        this.chksHeaderDataId = chksHeaderDataId;
        this.level = level;
    }

    // Add this constructor after existing constructors
    public DashboardDTO(Long id, String name, Long chksHeaderDataId) {
        this.id = id;
        this.name = name;
        this.chksHeaderDataId = chksHeaderDataId;
    }

} 