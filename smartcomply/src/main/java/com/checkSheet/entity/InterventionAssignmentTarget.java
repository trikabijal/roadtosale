package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

/**
 * Which audit_assignments are in scope for an Intervention. Materialised
 * once at activation; no runtime targeting branching. Single source of
 * truth regardless of whether the admin chose ALL / by region / manual.
 */
@NoArgsConstructor
@Data
@AllArgsConstructor
@Entity
@Table(name = "intervention_assignment_targets",
       uniqueConstraints = @UniqueConstraint(name = "uk_iat_intervention_inspection",
                                             columnNames = {"intervention_id", "inspection_id"}))
public class InterventionAssignmentTarget implements java.io.Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intervention_id", referencedColumnName = "id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_iat_intervention_id"))
    private Intervention intervention;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inspection_id", referencedColumnName = "id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_iat_inspection_id"))
    private Inspection inspection;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "deleted_at", length = 29)
    private Date deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", nullable = false)
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id")
    private User deletedBy;
}
