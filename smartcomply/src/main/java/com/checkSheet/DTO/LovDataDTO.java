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
public class LovDataDTO {
    private Long Id;
    private String name;
    private String value;
    private String valueType;


    //getLovData
    public LovDataDTO(String name, String value, String valueType) {
        this.name = name;
        this.value = value;
        this.valueType = valueType;
    }

}
