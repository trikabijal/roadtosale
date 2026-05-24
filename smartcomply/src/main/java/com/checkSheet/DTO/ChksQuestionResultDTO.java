package com.checkSheet.DTO;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.multipart.MultipartFile;

import com.checkSheet.constant.ChksQuestionResultObjectiveType;
import com.checkSheet.constant.ChksQuestionResultType;
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
public class ChksQuestionResultDTO {
    private Long id;
    private Long checksheetId;
    private Long chksHeaderId;
    private Long chksQuestionId;
    private Long cloneChksQuestionResultId;
    private Long selectedChksQuestionResultId;
    private ChksQuestionResultType answerType;
    private ChksQuestionResultObjectiveType chksQuestionResultObjectiveType;
    private Date createdAt;
    private Date updatedAt;
    private Double upperLimit;
    private Double lowerLimit;
    private String unit;
    private String matrixName;
    private List<String> chksMatrixRowNm;
    private List<String> chksMatrixColNm;
    private Long noOfResults = 1l;
    private Long noOfRows;
    private Long noOfColumns;
    private String chksMatrixColName;
    private String chksMatrixRowName;
    private List<ChksQuestionResultOptionDTO> chksQuestionResultOptions;
    private List<ChksQuestionResultMatrixDTO> chksQuestionResultMatrices;
    private MultipartFile matrixFile;
    private String matrixFileLocation;
    private String userAnswer;
    private List<UserChecksheetMatrixAnswerDTO> matrixAnswers;
    private List<UserChecksheetAnswerFileDTO> userChecksheetAnswerFiles;
    private String matrixFileUrl;
    private Boolean isOptional;
    private Boolean isNotApplicable;
    /** Gemini-Flash photo assessment for this answer, when present.
     *  Null when no AI run yet (no photo, or assessment failed). The
     *  audit-report renders "AI: NOT AVAILABLE" in that case. */
    private AiAssessmentDTO aiAssessment;

    public void setUnit(String unit) {
        this.unit = unit != null ? unit.trim() : null;
    }

    public void setMatrixName(String matrixName) {
        this.matrixName = matrixName != null ? matrixName.trim() : null;
    }

    public void setChksMatrixColName(String chksMatrixColName) {
        this.chksMatrixColName = chksMatrixColName != null ? chksMatrixColName.trim() : null;
    }

    public void setChksMatrixRowName(String chksMatrixRowName) {
        this.chksMatrixRowName = chksMatrixRowName != null ? chksMatrixRowName.trim() : null;
    }

    //getResultData
    public ChksQuestionResultDTO(
            Long id,
            Long checksheetId,
            Long chksHeaderId,
            Long chksQuestionId,
            ChksQuestionResultType answerType,
            ChksQuestionResultObjectiveType chksQuestionResultObjectiveType,
            Double upperLimit,
            Double lowerLimit,
            String unit,
            String matrixName,
            String matrixRowHeaderNames,
            String matrixColumnHeaderNames,
            Long noOfResults,
            Long noOfRows,
            Long noOfColumns,
            String chksMatrixRowName,
            String chksMatrixColName,
            Boolean isOptional
    ) {
        this.id = id;
        this.checksheetId = checksheetId;
        this.chksHeaderId = chksHeaderId;
        this.chksQuestionId = chksQuestionId;
        this.answerType = answerType;
        this.chksQuestionResultObjectiveType = chksQuestionResultObjectiveType;
        this.upperLimit = upperLimit;
        this.lowerLimit = lowerLimit;
        this.unit = unit;
        this.matrixName = matrixName;
        this.chksMatrixRowNm = convertStringToList(matrixRowHeaderNames);
        this.chksMatrixColNm = convertStringToList(matrixColumnHeaderNames);
        this.noOfResults = noOfResults;
        this.noOfRows = noOfRows;
        this.noOfColumns = noOfColumns;
        this.chksMatrixRowName = chksMatrixRowName;
        this.chksMatrixColName = chksMatrixColName;
        this.isOptional = isOptional;
    }

    private List<String> convertStringToList(String str) {
        try {
            if (str == null || str.isEmpty()) {
                return List.of();
            }
            return Arrays.stream(str.replaceAll("[{}\"]", "").trim().split(","))
                    .filter(s -> !s.isEmpty())
                    .map(String::trim)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

}
