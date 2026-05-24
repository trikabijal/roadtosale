package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAssessmentDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long chksQuestionResultId;
    private String suggestedJudgement;
    private String explanation;
    private Double confidence;
    private String photoUrl;
    private Date assessedAt;

    public AiAssessmentDTO(Long id, Long userChecksheetId, Long chksQuestionResultId,
                           String suggestedJudgement, String explanation, Double confidence,
                           String photoUrl, Date assessedAt) {
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.chksQuestionResultId = chksQuestionResultId;
        this.suggestedJudgement = suggestedJudgement;
        this.explanation = explanation;
        this.confidence = confidence;
        this.photoUrl = photoUrl;
        this.assessedAt = assessedAt;
    }
}
