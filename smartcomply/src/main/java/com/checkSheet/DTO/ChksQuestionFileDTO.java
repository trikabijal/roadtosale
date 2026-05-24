package com.checkSheet.DTO;

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
public class ChksQuestionFileDTO {
    private Long id;
    private Long checksheetId;
    private Long chksHeaderId;
    private Long chksQuestionId;
    private Long chksHeaderDataId;
    private String path;
    private String url;
    private String description;
    private Date createdAt;
    private Date updatedAt;
    public ChksQuestionFileDTO(Long id, String path, Long chksQuestionId) {
        this.id = id;
        this.path = path;
        this.chksQuestionId = chksQuestionId;
    }

    public void setPath(String path) {
        this.path = path != null ? path.trim() : null;
    }

    public void setDescription(String description) {
        this.description = description != null ? description.trim() : null;
    }
}
