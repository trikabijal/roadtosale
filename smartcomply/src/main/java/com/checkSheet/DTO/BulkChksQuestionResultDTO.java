package com.checkSheet.DTO;

import java.util.List;

import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.checkSheet.constant.ChksQuestionResultType;
import com.checkSheet.constant.ChksQuestionResultObjectiveType;
import org.springframework.web.multipart.MultipartFile;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkChksQuestionResultDTO {
    private Long checksheetId;
    private List<Long> chksHeaderIds;
    private List<Long> chksQuestionIds;
    private ChksQuestionResultType answerType;
    private ChksQuestionResultObjectiveType chksQuestionResultObjectiveType;
    private Double upperLimit;
    private Double lowerLimit;
    private String unit;
    private Long noOfResults = 1L;
//    private String matrixFileLocation;
    private MultipartFile matrixFile;
    private String matrixName;
    private List<String> chksMatrixRowNm;
    private List<String> chksMatrixColNm;
    private Long noOfRows;
    private Long noOfColumns;
    private String chksMatrixColName;
    private String chksMatrixRowName;
    private Boolean isOptional;
    private List<ChksQuestionResultMatrixDTO> chksQuestionResultMatrices;
    
    // For subjective_condition
    private List<ChksQuestionResultOptionDTO> chksQuestionResultOptions;
}
