package com.checkSheet.DTO;

import com.checkSheet.constant.ChecksheetDataValidationStatusType;
import com.checkSheet.constant.ChecksheetValidationStatusType;
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
public class UserChecksheetValidationHistoryDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long checksheetId;
    private ChecksheetDataValidationStatusType status;
    private String remarks;
    private Long dataValidatorUserId;
    private Long version;
    private Date createdAt;
    private Date updatedAt;
    private Date validatedAt;
    private String firstName;
    private String lastName;

    public void setRemarks(String remarks) {
        this.remarks = remarks != null ? remarks.trim() : null;
    }

    //getUserChecksheetValidatorHistoryByInspectionId
    public UserChecksheetValidationHistoryDTO(Long id, String remarks,
                                              ChecksheetDataValidationStatusType status, Long dataValidatorUserId,
                                              Date validatedAt, Long version, String firstName, String lastName) {
        System.out.printf("status : "  +  status);
        this.id = id;
        this.status = status;
        this.remarks = remarks;
        this.dataValidatorUserId = dataValidatorUserId;
        this.validatedAt = validatedAt;
        this.version = version;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public UserChecksheetValidationHistoryDTO(Long id, Long userChecksheetId, String remarks,
                                              ChecksheetDataValidationStatusType status,
                                              Date validatedAt, Long version, String firstName, String lastName) {
        System.out.printf("status : "  +  status);
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.status = status;
        this.remarks = remarks;
        this.validatedAt = validatedAt;
        this.version = version;
        this.firstName = firstName;
        this.lastName = lastName;
    }
}
