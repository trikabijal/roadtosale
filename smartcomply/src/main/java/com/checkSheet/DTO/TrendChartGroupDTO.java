package com.checkSheet.DTO;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrendChartGroupDTO {
    private Long userChecksheetAnswerId;
    private Long questionId;
    private String unit;
    private Double upperLimit;
    private Double lowerLimit;
    private String answerType;
    private String objectiveType;
    private List<TrendChartAnswerDTO> answers;
    private Double min;
    private Double max;
    private Double avg;
    private Double sigma;
    private Double cp;
    private Double cpl;
    private Double cpu;
    private Double cpk;
}
