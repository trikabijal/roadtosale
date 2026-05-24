package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Data
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NpdMasterDTO {
    private Long id;
    private Long checksheetId;
    private String checksheetName; // for aggregated response
    private Long createdBy;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date npdDate;
    private String shift;
    private Boolean isException;
    private String remarks;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private Date createdAt;
    // Derived flag: true if npdDate is today or in future
    private Boolean isDeletable;

    // Bulk creation fields (existing)
    private List<Long> checksheetIds;
    private List<String> shifts;

    // New bulk payload: list of days each with shifts
    private List<NpdDayDTO> npdDays;

    // Aggregated search filters (optional)
    private String checksheetNameLike;
    private String frequencyOfCheck; // enum name as string
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startDate;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endDate;
    // Pagination
    private Integer page; // 0-based
    private Integer size; // page size
    // Aggregated result field
    private Long npdCount;

    @Data
    @Getter
    @Setter
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NpdDayDTO {
        @JsonFormat(pattern = "yyyy-MM-dd")
        private Date npdDate;
        // Note: field name per request payload
        private List<String> shifts;
    }

    // General-purpose IDs list for bulk operations (e.g., delete)
    private List<Long> ids;
}


