package com.checkSheet.DTO;

import com.checkSheet.constant.ChecksheetDataValidationStatusType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserChecksheetValidationDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long checksheetId;
    private ChecksheetDataValidationStatusType status;
    private String remarks;
    private Long dataValidatorUserId;
    private Date createdAt;
    private Date updatedAt;
    private Date validatedAt;
    private String firstName;
    private String lastName;
    private List<UserChecksheetValidationHistoryDTO> userChecksheetValidatorHistory;

    public void setRemarks(String remarks) {
        this.remarks = remarks != null ? remarks.trim() : null;
    }

    // Constructor for getting user checksheet validation details
    public UserChecksheetValidationDTO(Long id, String remarks, 
            ChecksheetDataValidationStatusType status, Long dataValidatorUserId, 
            Date validatedAt, String firstName, String lastName) {
        this.id = id;
        this.status = status;
        this.remarks = remarks;
        this.dataValidatorUserId = dataValidatorUserId;
        this.validatedAt = validatedAt;
        this.firstName = firstName;
        this.lastName = lastName;
    }
}
