package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SurprizeChecksheetFieldDTO {
    private Long id;
    private Long surpriseChecksheetId;
    private Long responsibleUserId;
    private String responsibleUserUsername;
    private Long departmentId;
    private String concern;
    private String remarks;
    private List<MultipartFile> files = new ArrayList<>();
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date creationDate;
    private Long localId;
}
