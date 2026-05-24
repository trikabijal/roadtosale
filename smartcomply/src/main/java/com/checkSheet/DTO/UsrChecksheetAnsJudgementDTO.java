package com.checkSheet.DTO;

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
public class UsrChecksheetAnsJudgementDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long chksQuestionId;
    private String judgement;
    private String remarks;
    private String localId;
    private Date submittedAt;
    private String firstName;
    private String lastName;
    private String username;

    public UsrChecksheetAnsJudgementDTO(Long id, Long userChecksheetId, Long chksQuestionId, String judgement, String remarks){
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.chksQuestionId = chksQuestionId;
        this.judgement = judgement;
        this.remarks = remarks;
    }

//    getChecksheetSummaryQueJudgements
    public UsrChecksheetAnsJudgementDTO(Long userChecksheetId, Long chksQuestionId, String judgement, Date submittedAt, String firstName, String lastName, String username){
        this.inspectionId = userChecksheetId;
        this.chksQuestionId = chksQuestionId;
        this.judgement = judgement;
        this.submittedAt = submittedAt;
        this.username = username;
        this.firstName = firstName;
        this.lastName = lastName;
    }
}
