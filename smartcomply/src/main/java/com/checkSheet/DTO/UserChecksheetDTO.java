package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
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
public class UserChecksheetDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date createdAt;
    private Date updatedAt;
    private ChecksheetDTO checksheet;
    private Long checksheetId;
    private Long localId;
    private UserDTO userDTO;
    private String shift;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date startedAt;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS")
    private Date submittedAt;
    private Byte submissionVersion = 0;
    private Short frequencyOfFreqOfChkCnt;
    // Write path: clients send EITHER auditAssignmentId (original audit fill)
    // OR interventionAssignmentId (re-inspection wave). Server resolves audit,
    // location, and checksheet from whichever is set. The DB-level CHECK
    // constraint on inspections enforces exactly-one-parent.
    private Long auditAssignmentId;
    private Long interventionAssignmentId;
    // Read path (response only): derived for the consumer's convenience.
    private Long auditId;
    private Long auditeeLocationId;
    private String auditName;
    private String checksheetName;
    public UserChecksheetDTO(Long id, String status, Long checksheetId,String shift,Date startedAt,Date submittedAt, Byte submissionVersion){
        this.id = id;
        this.status = status;
        this.checksheetId = checksheetId;
        this.shift = shift;
        this.startedAt = startedAt;
        this.submittedAt = submittedAt;
        this.submissionVersion = submissionVersion;
    }
}
