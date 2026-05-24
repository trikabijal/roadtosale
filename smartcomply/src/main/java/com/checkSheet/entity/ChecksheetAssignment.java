package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "checksheet_assignment", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_checksheet_assignment_checksheet_auditor_location",
                columnNames = {"checksheet_id", "auditor_id", "auditee_location_id"}
        )
})
public class ChecksheetAssignment implements java.io.Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", nullable = false, foreignKey = @ForeignKey(name = "fk_checksheet_assignment_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_assignment_updated_by_user_id"))
    private User updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_assignment_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", referencedColumnName = "id", nullable = false, foreignKey = @ForeignKey(name = "fk_checksheet_assignment_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auditor_id", referencedColumnName = "id", nullable = false, foreignKey = @ForeignKey(name = "fk_checksheet_assignment_auditor_id_users_id"))
    private User auditor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auditee_location_id", referencedColumnName = "id", nullable = false, foreignKey = @ForeignKey(name = "fk_checksheet_assignment_auditee_location_id_auditee_locations_id"))
    private AuditeeLocation auditeeLocation;
}
