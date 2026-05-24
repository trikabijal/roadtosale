package com.checkSheet.DTO;

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
public class ChksHeaderDTO {
    private Long id;
    private Long checksheetId;
    private Long chksHeaderId;
    private String name;
    private Boolean isResultColumn;
    private Date createdAt;
    private Date updatedAt;
    private Boolean isTraceable;
    private Byte summaryReportLevel;

    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    //getChksHeaderByChecksheetId
    public ChksHeaderDTO(Long id, String name, Long checksheetId, Long chksHeaderId, Boolean isResultColumn, Boolean isTraceable, Byte summaryReportLevel) {
        this.id = id;
        this.name = name;
        this.checksheetId = checksheetId;
        this.chksHeaderId = chksHeaderId;
        this.isResultColumn = isResultColumn;
        this.isTraceable = isTraceable;
        this.summaryReportLevel = summaryReportLevel;
    }
}
