package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UsrChksheetAnsJudgementFileDTO {
    private Long id;
    @com.fasterxml.jackson.annotation.JsonAlias({"userChecksheetId"})
    private Long inspectionId;
    private Long usrChksheetAnsJudgementId;
    private MultipartFile file;
    private String path;
    private String url;
    private List<Long> usrChksheetAnsJudgementFileIds;

    public UsrChksheetAnsJudgementFileDTO(Long id, Long userChecksheetId, Long usrChksheetAnsJudgementId, String path){
        this.id = id;
        this.inspectionId = userChecksheetId;
        this.usrChksheetAnsJudgementId = usrChksheetAnsJudgementId;
        this.path = path;
    }
}
