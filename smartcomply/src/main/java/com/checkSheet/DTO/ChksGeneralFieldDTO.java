package com.checkSheet.DTO;

import com.checkSheet.constant.ChecksheetFrequencyType;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChksGeneralFieldDTO {
    private Long id;
    private Long checksheetId;
    private String name;
    private String answer;
    private Date createdAt;
    private Date updatedAt;


    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    //getChksGeneralFieldByChecksheetId
    public ChksGeneralFieldDTO(Long id, String name, Long checksheetId) {
        this.id = id;
        this.name = name;
        this.checksheetId = checksheetId;
    }

}
