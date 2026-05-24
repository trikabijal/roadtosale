package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.util.List;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChksQuestionDTO {
    private Long id;
    private Long checksheetId;
    private Long chksHeaderId;
    private Long chksHeaderDataId;
    private List<Long> questionIds;
    private List<MultipartFile> chksQuestionImages;
    private String name;
    private String description;
    private Date createdAt;
    private Date updatedAt;
    private Integer orderNo;
    private List<ChksQuestionResultDTO> chksQuestionResults;
    private List<ChksQuestionFileDTO> chksQuestionDataFiles;
    private String judgement;
    private String remarks;
    private List<ChksHeaderDataFileDTO> judgementFiles;


    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }
    public void setDescription(String description) {
        this.description = description != null ? description.trim() : null;
    }

    //getChksQuestionByChecksheetHeaderDataId
    public ChksQuestionDTO(Long id, String name, String description, Integer orderNo) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.orderNo = orderNo;
    }

    //getChksQuestionByChecksheetId
    public ChksQuestionDTO(Long id, String name, String description, Long chksHeaderId,Long chksHeaderDataId, Integer orderNo) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.chksHeaderId = chksHeaderId;
        this.chksHeaderDataId = chksHeaderDataId;
        this.orderNo = orderNo;
    }
}
