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
public class UserChecksheetAnswerFileDTO {

    private Long id;
    private Long userChecksheetAnswerId;
    private String mimeType;
    private Long fileSizeBytes;
    private String fileHashSha256;
    private String path;
    private String thumbnailPath;
    private String url;
    private MultipartFile file;
    private List<Long> userChecksheetAnswerFileIds;
}
