package com.checkSheet.DTO;

import com.checkSheet.constant.ChecksheetApprovalStatusType;
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
public class ChecksheetApprovalHistoryDTO {
    private Long id;
    private Long checksheetId;
    private ChecksheetApprovalStatusType status;
    private String remarks;
    private Long approverUserId;
    private Long version;
    private Date createdAt;
    private Date updatedAt;
    private Date approvedAt;
    private String firstName;
    private String lastName;

    public void setRemarks(String remarks) {
        this.remarks = remarks != null ? remarks.trim() : null;
    }

    //getChecksheetApproverHistoryByChecksheetId
    public ChecksheetApprovalHistoryDTO(Long id, String remarks, ChecksheetApprovalStatusType status, Long approverUserId, Date approvedAt,
                                        Long version, String firstName, String lastName) {
        this.id = id;
        this.status = status;
        this.remarks = remarks;
        this.approverUserId = approverUserId;
        this.approvedAt = approvedAt;
        this.version = version;
        this.firstName = firstName;
        this.lastName = lastName;
    }

}
