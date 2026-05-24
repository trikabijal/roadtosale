package com.checkSheet.DTO;

import java.util.Date;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TrendChartAnswerDTO {
    private Short judgement;
    private Long chksQuestionId;
    private Long chksQuestionResultId;
    private Date checkDate;
    private Date answerDate;
    private Date submittedAt;
    private String status;
    private String answer;
    private String firstName;
    private String lastName;
    private String username;
    private Long userChksId;
} 