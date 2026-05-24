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
public class ChksGeneralFieldValueDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long chksGeneralFieldId;
    private String value;
    private Long localId;

    public ChksGeneralFieldValueDTO(
        Long id,
        Long userChecksheetId,
        Long chksGeneralFieldId,
        String value
    ){
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.chksGeneralFieldId = chksGeneralFieldId;
        this.value = value;
    }
}
