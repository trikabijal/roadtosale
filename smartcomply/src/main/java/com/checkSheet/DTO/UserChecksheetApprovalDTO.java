package com.checkSheet.DTO;

import com.checkSheet.constant.ChecksheetDataApprovalStatusType;
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
public class UserChecksheetApprovalDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long checksheetId;
    private ChecksheetDataApprovalStatusType status;
    private String remarks;
    private Long dataApproverUserId;
    private Date createdAt;
    private Date updatedAt;
    private Date approvedAt;
    private Date deletedAt;
    private Long createdBy;
    private Long updatedBy;
    private Long deletedBy;

    public void setRemarks(String remarks) {
        this.remarks = remarks != null ? remarks.trim() : null;
    }
}
