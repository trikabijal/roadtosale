package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "surprise_checksheet_fields")
public class SurpriseChecksheetField {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "surprise_checksheet_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "surprise_checksheet_fields_fk_surprise_checksheet_id"))
    private SurpriseChecksheet surpriseChecksheet;

    @Column(name = "concern", columnDefinition = "text")
    private String concern;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "surprise_checksheet_fields_fk_departments_id"))
    private Department department;

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "responsible_user_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "surprise_checksheets_fields_fk_responsible_user_id"))
    private User responsibleUser;

    @Column(name = "file_paths", columnDefinition = "_varchar ")
    private List<String> filePaths = new ArrayList<>();

    @Temporal(TemporalType.DATE)
    @Column(name = "creation_date", length = 10)
    private Date creationDate;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at", length = 29, columnDefinition = "TIMESTAMP")
    private Date updatedAt;

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = new Date();
    }

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "deleted_at", length = 29)
    private Date deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "surprise_checksheets_fields_fk_created_by"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "surprise_checksheets_fields_fk_deleted_by"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "surprise_checksheets_fields_fk_updated_by"))
    private User updatedBy;

}
