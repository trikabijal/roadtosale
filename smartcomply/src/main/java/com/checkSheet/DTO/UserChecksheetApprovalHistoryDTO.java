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
public class UserChecksheetApprovalHistoryDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long checksheetId;
    private ChecksheetDataApprovalStatusType status;
    private String remarks;
    private Long dataApproverUserId;
    private Byte version;
    private Date createdAt;
    private Date updatedAt;
    private Date approvedAt;
    private String firstName;
    private String lastName;

    public void setRemarks(String remarks) {
        this.remarks = remarks != null ? remarks.trim() : null;
    }

    //getChecksheetDataApproverHistoryByChecksheetId
    public UserChecksheetApprovalHistoryDTO(Long id, String remarks, ChecksheetDataApprovalStatusType status, 
                                          Long dataApproverUserId, Date approvedAt, Byte version,
                                          String firstName, String lastName) {
        this.id = id;
        this.status = status;
        this.remarks = remarks;
        this.dataApproverUserId = dataApproverUserId;
        this.approvedAt = approvedAt;
        this.version = version;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public UserChecksheetApprovalHistoryDTO(Long id, Long userChecksheetId, String remarks, ChecksheetDataApprovalStatusType status, Date approvedAt, Byte version,
                                            String firstName, String lastName) {
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.status = status;
        this.remarks = remarks;
        this.approvedAt = approvedAt;
        this.version = version;
        this.firstName = firstName;
        this.lastName = lastName;
    }
}
