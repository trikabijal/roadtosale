package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

/**
 * Intervention — top-level "Improvement Campaign" in PRD parlance.
 * Mirrors {@link Audit} structurally; the per-location instance is
 * {@link Inspection} (= "Improvement Plan" in PRD).
 *
 * The PRD-internal vocabulary maps:
 *   PRD "Campaign"  →  Intervention
 *   PRD "Plan"      →  Inspection
 * UI labels keep the PRD names; code uses Intervention so the parallel
 * with Audit/Inspection is obvious.
 */
@NoArgsConstructor
@Data
@AllArgsConstructor
@Entity
@Table(name = "interventions")
public class Intervention implements java.io.Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audit_id", referencedColumnName = "id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_interventions_audit_id"))
    private Audit audit;

    @Column(name = "name", nullable = false, columnDefinition = "varchar")
    private String name;

    @Column(name = "theme", columnDefinition = "text")
    private String theme;

    /** P1 | P2 | P3. */
    @Column(name = "priority", nullable = false, columnDefinition = "varchar")
    private String priority;

    @Temporal(TemporalType.DATE)
    @Column(name = "target_date")
    private Date targetDate;

    /** DRAFT | ACTIVE | CLOSED. */
    @Column(name = "status", nullable = false, columnDefinition = "varchar default 'DRAFT'")
    private String status = "DRAFT";

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "activated_at", length = 29)
    private Date activatedAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "closed_at", length = 29)
    private Date closedAt;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at", length = 29)
    private Date updatedAt;

    @PreUpdate
    private void onUpdate() { this.updatedAt = new Date(); }

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "deleted_at", length = 29)
    private Date deletedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_interventions_created_by"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_interventions_updated_by"))
    private User updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_interventions_deleted_by"))
    private User deletedBy;
}
