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
public class UserChecksheetMatrixAnswerDTO {
    private Long id;
    private String answer;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long chksQuestionId;
    private Long chksQuestionResultId;
    private Long rowId;
    private Long columnId;
    private Short judgement;
    private Long chksQuestionResultMatrixId;
    private Integer orderNo;
    private String result;
    private String mcResult;
    private String chksMatrixColHdr;
    private String chksMatrixRowHdr;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date answeredAt;
} 