package com.checkSheet.DTO;

import com.checkSheet.constant.ChecksheetValidationStatusType;
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
public class ChecksheetValidationDTO {
    private Long id;
    private Long checksheetId;
    private ChecksheetValidationStatusType status;
    private String remarks;
    private Long validatorUserId;
    private Date createdAt;
    private Date updatedAt;
    private Date validatedAt;
    private String firstName;
    private String lastName;
    private List<ChecksheetValidationHistoryDTO> checksheetValidatorHistory;

    public void setRemarks(String remarks) {
        this.remarks = remarks != null ? remarks.trim() : null;
    }

    //getChecksheetValidatorByChecksheetId
    public ChecksheetValidationDTO(Long id, String remarks, ChecksheetValidationStatusType status, Long validatorUserId, Date validatedAt, String firstName,
                                   String lastName) {
        this.id = id;
        this.status = status;
        this.remarks = remarks;
        this.validatorUserId = validatorUserId;
        this.validatedAt = validatedAt;
        this.firstName = firstName;
        this.lastName = lastName;
    }
}
