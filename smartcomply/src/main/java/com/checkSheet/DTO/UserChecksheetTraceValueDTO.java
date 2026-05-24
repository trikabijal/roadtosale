package com.checkSheet.DTO;

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
public class UserChecksheetTraceValueDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long chksHeaderDataId;
    private String traceValue;
    private Long localId;

    public UserChecksheetTraceValueDTO(
        Long id,
        Long userChecksheetId,
        Long chksHeaderDataId,
        String traceValue
    ){
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.chksHeaderDataId = chksHeaderDataId;
        this.traceValue = traceValue;
    }
}
