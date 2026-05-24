package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserChecksheetAnswerDTO {
    private Long id;
    /** Frontend & mobile clients still send "userChecksheetId" — accept both. */
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long chksQuestionResultId;
    private Long chksQuestionId;
    private String answer;
    private Long chksQuestionRsltOptionId;
    private Long chksQuestionResultMatrixId;
    private String result;
    private String mcResult;
    private String remarks;
    private Long localId;
    private Integer orderNo;
    private Boolean isNotApplicable;

    private Short judgement;
    private String checksheetQuestionJudgement;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date answeredAt;

    //getUserChksAnswers
    public UserChecksheetAnswerDTO(
        Long id,
        Long userChecksheetId,
        Long chksQuestionResultId,
        String answer,
        Long chksQuestionRsltOptionId,
        Short judgement,
        Date answeredAt,
        Boolean isNotApplicable
    ){
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.chksQuestionResultId = chksQuestionResultId;
        this.answer = answer;
        this.chksQuestionRsltOptionId = chksQuestionRsltOptionId;
        this.judgement = judgement;
        this.answeredAt = answeredAt;
        this.isNotApplicable = isNotApplicable;
    }
    public UserChecksheetAnswerDTO(
        Long id,
        Long userChecksheetId,
        Long chksQuestionResultId,
        Long chksQuestionResultMatrixId,
        String result,
        Integer orderNo,
        Short judgement,
        String mcResult,
        Date answeredAt
    ){
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.chksQuestionResultId = chksQuestionResultId;
        this.result = result;
        this.chksQuestionResultMatrixId = chksQuestionResultMatrixId;
        this.orderNo = orderNo;
        this.judgement = judgement;
        this.mcResult = mcResult;
        this.answeredAt = answeredAt;
    }

//    getChecksheetSummaryAnswers
    public UserChecksheetAnswerDTO(
            Long id,
            Long userChecksheetId,
            Long chksQuestionResultId,
            String answer,
            Long chksQuestionRsltOptionId,
            Long chksQuestionId,
            Short judgement
    ){
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.chksQuestionResultId = chksQuestionResultId;
        this.answer = answer;
        this.chksQuestionRsltOptionId = chksQuestionRsltOptionId;
        this.chksQuestionId = chksQuestionId;
        this.judgement = judgement;
    }
}