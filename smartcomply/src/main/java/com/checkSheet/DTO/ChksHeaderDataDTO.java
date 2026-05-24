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
public class ChksHeaderDataDTO {
    private Long id;
    private Long chksHeaderId;
    private Long chksHeaderDataId;
    private Long checksheetId;
    private String name;
    private String description;
    private Integer orderNo;
    private Date createdAt;
    private Date updatedAt;
    private Long level;
    private Integer checkHeaderFirstFileURL;
    private List<MultipartFile> chksHeaderDataImages;
    private List<ChksQuestionDTO> questions;
    private List<ChksHeaderDataDTO> children;
    private List<ChksHeaderDataFileDTO> chksHeaderDataFiles;
    private List<Long> chksChildHeaderDataIds;

    //getChksHeaderDataByChksId
    public ChksHeaderDataDTO(
        Long id, String name, String description, Long checksheetId, Long chksHeaderId, Long chksHeaderDataId, Long level,
        Integer orderNo
    ){
        this.id = id;
        this.name = name;
        this.description = description;
        this.checksheetId = checksheetId;
        this.chksHeaderId = chksHeaderId;
        this.chksHeaderDataId = chksHeaderDataId;
        this.level = level;
        this.orderNo = orderNo;
    }
}
