package com.checkSheet.DTO;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecentSubmissionDTO {
    
    // Request fields
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    
    private List<Long> departmentIds;
    private List<String> statusFilter;
    private Integer limit;
    private Integer offset;
    
    // Response fields
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long checksheetId;
    private String checksheetName;
    private Long version;
    private Long departmentId;
    private String departmentName;
    private String operatorName;
    private String operatorUsername;
    private String status;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date submittedAt;
    
    private Long okCount;
    private Long notOkCount;
    private String shift;
    private String frequencyOfCheck;
    
    // Constructor for SQL result mapping
    public RecentSubmissionDTO(Long userChecksheetId, Long checksheetId, String checksheetName, 
                               Long version, Long departmentId, String departmentName,
                               String firstName, String lastName, String username, String status,
                               Date submittedAt, Long okCount, Long notOkCount, 
                               String shift, String frequencyOfCheck) {
        this.inspectionId = userChecksheetId;
        this.checksheetId = checksheetId;
        this.checksheetName = checksheetName;
        this.version = version;
        this.departmentId = departmentId;
        this.departmentName = departmentName;
        this.operatorName = firstName + " " + lastName;
        this.operatorUsername = username;
        this.status = status;
        this.submittedAt = submittedAt;
        this.okCount = okCount != null ? okCount : 0L;
        this.notOkCount = notOkCount != null ? notOkCount : 0L;
        this.shift = shift;
        this.frequencyOfCheck = frequencyOfCheck;
    }
}
