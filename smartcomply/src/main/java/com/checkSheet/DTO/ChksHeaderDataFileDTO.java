package com.checkSheet.DTO;

import com.checkSheet.service.AWSS3Service;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChksHeaderDataFileDTO {
    private Long id;
    private Long chksHeaderId;
    private Long chksHeaderDataId;
    private Long checksheetId;
    private String name;
    private String path;
    private String url;
    private String description;
    private Date createdAt;
    private Date updatedAt;

    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    //getChksHeaderDataFileByChecksheetId
    public ChksHeaderDataFileDTO(Long id, String path, Long chksHeaderDataId) {
        this.id = id;
        this.path = path;
        this.chksHeaderDataId = chksHeaderDataId;
    }
}
