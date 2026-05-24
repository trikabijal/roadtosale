package com.checkSheet.DTO;

import com.checkSheet.constant.ChksQuestionResultType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.List;


@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChksQuestionResultOptionDTO {
    private Long id;
    private Long checksheetId;
    private Long chksHeaderId;
    private Long chksQuestionId;
    private Long chksQuestionResultId;
    private String option;
    private String judgement;
    private Date createdAt;
    private Date updatedAt;



    public void setOption(String option) {
        this.option = option != null ? option.trim() : null;
    }

    public void setJudgement(String judgement) {
        this.judgement = judgement != null ? judgement.trim() : null;
    }

    public ChksQuestionResultOptionDTO(
        Long id,
        String option,
        String judgement
    ) {
        this.id = id;
        this.option = option;
        this.judgement = judgement;
    }

    public ChksQuestionResultOptionDTO(
        Long id,
        String option,
        String judgement,
        Long chksQuestionResultId
    ) {
        this.id = id;
        this.option = option;
        this.judgement = judgement;
        this.chksQuestionResultId = chksQuestionResultId;
    }
}
