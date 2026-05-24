package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppVersionDTO {
    private Long id;
    private Long version;
    private String url;
    private String os;
    private String versionName;
    private Boolean isForcefullyUpdate;

    //getAppVersionByOs
    public AppVersionDTO(Long id, Long version, String  url, String os, Boolean isForcefullyUpdate, String versionName) {
        this.id = id;
        this.version = version;
        this.url = url;
        this.os = os;
        this.isForcefullyUpdate = isForcefullyUpdate;
        this.versionName = versionName;
    }

}
