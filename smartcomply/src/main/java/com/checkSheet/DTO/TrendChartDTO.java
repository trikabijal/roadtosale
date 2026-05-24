package com.checkSheet.DTO;

import java.util.Date;
import java.util.List;

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
public class TrendChartDTO {
    private Long id;
    private Long userChecksheetAnswerId;
    private Short judgement;
    private String status;
    private String answer;
    private String firstName;
    private String lastName;
    private String unit;
    private Long chksQuestionId;
    private Double upperLimit;
    private Double lowerLimit;
    private String answerType;
    private String objectiveType;
    private String username;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date checkDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date answerDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date submittedAt;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    
    private List<Long> checksheetIds;
    private List<Long> chksQuestionIds;
    private List<Long> chksQuestionResultIds;
    private List<ParamValDTO> parameters;

    // getTrendChartData
    public TrendChartDTO(Long id,Long userChecksheetAnswerId, Long chksQuestionId, Short judgement,
                        Date checkDate, Date answerDate, Date submittedAt, String status, String answer,
                        String firstName, String lastName, String username) {
        this.id = id;
        this.userChecksheetAnswerId = userChecksheetAnswerId;
        this.chksQuestionId = chksQuestionId;
        this.judgement = judgement;
        this.checkDate = checkDate;
        this.answerDate = answerDate;
        this.submittedAt = submittedAt;
        this.status = status;
        this.answer = answer;
        this.firstName = firstName;
        this.lastName = lastName;
        this.username = username;
    }
} 