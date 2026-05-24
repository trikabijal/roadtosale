package com.checkSheet.DTO;

import com.checkSheet.constant.ChecksheetApprovalStatusType;
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
public class ChecksheetApprovalDTO {
    private Long id;
    private Long checksheetId;
    private ChecksheetApprovalStatusType status;
    private String remarks;
    private Long approverUserId;
    private Date createdAt;
    private Date updatedAt;
    private Date approvedAt;
    private Date implementationDate;


    public void setRemarks(String remarks) {
        this.remarks = remarks != null ? remarks.trim() : null;
    }

}
