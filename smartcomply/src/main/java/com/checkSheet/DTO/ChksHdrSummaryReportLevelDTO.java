package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChksHdrSummaryReportLevelDTO {
    private Long checksheetId;
    private List<Long> levelOneHeaderIds = new ArrayList<>();
    private List<Long> levelTwoHeaderIds = new ArrayList<>();
}
