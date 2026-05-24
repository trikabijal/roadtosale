package com.checkSheet.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/** Inbound for PUT /api/intervention/{id}. Drafts only. */
@Data @NoArgsConstructor @AllArgsConstructor
public class InterventionUpdateDTO {
    private String name;
    private String theme;
    private String priority;
    private Date targetDate;
}
