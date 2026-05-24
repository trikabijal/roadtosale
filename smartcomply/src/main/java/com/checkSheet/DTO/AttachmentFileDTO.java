package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttachmentFileDTO {
    private String imageName;
    private String imageURL;
    private String filePath;
    private byte[] image;
}
