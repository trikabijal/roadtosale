package com.checkSheet.DTO.request;

import com.checkSheet.DTO.ChksQuestionDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChksQuestionBulkDTO {
    private Long chksHeaderDataId;
    private Long checksheetId;
    private Integer orderNo;
    private List<ChksQuestionDTO> questions;
}
