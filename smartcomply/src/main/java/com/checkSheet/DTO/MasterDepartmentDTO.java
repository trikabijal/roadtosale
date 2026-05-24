package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Date;
import java.util.List;

/**
 * DTO for Master Department Management (pure CRUD operations).
 * This is separate from DepartmentDTO which handles Department Admin assignment.
 */
@Getter
@Setter
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MasterDepartmentDTO {
    
    private Long id;
    private String name;
    
    private Date createdAt;
    private Date updatedAt;
    
    // Created by user info
    private Long createdById;
    private String createdByName;
    
    // Pagination fields
    private Integer currentPage;
    private Integer perPageRecord;
    private String search;
    
    // UI flags
    private Boolean isEditable;
    private Boolean isDeletable;
    
    // Section count under this master department
    private Integer sectionCount;
    
    // Sections list (for edit view - managing sections within master department)
    private List<MasterDepartmentSectionDTO> sections;
    
    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }
    
    // Constructor for search query results
    public MasterDepartmentDTO(Long id, String name, Date createdAt, Date updatedAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    
    // Constructor for simple dropdown list
    public MasterDepartmentDTO(Long id, String name) {
        this.id = id;
        this.name = name;
    }
    
    /**
     * Inner DTO for sections (sub-departments) within a master department
     */
    @Getter
    @Setter
    @Builder
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MasterDepartmentSectionDTO {
        private Long id;
        private String name;
        private Date createdAt;
        private Date updatedAt;
        private Boolean isEditable;
        private Boolean isDeletable;
        
        // Constructor for section query results
        public MasterDepartmentSectionDTO(Long id, String name, Date createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
        }
    }
}
