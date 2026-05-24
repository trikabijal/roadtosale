package com.checkSheet.entity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;

import com.checkSheet.DTO.UserChecksheetDTO;

import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.SqlResultSetMappings;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Inspection — one runtime audit visit at one location.
 *
 * <p>Replaces the old three-table model (audit_assignments,
 * intervention_assignments, inspections) collapsed by V1.28. One row
 * captures the full lifecycle: ASSIGNED at admin-create time, transitions
 * through IN_PROGRESS → SUBMITTED → VALIDATED → APPROVED on the same row.
 *
 * <p>{@code kind} discriminates the parent: AUDIT means {@link #audit} is
 * set (original audit visit), INTERVENTION means {@link #intervention} is
 * set (re-inspection visit driven by an improvement campaign). Exactly one
 * of the two is non-null — DB CHECK constraint enforces.
 */
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getDeclinedUserChecksheets",
                classes = @ConstructorResult(
                        targetClass = UserChecksheetDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "status", type = String.class),
                                @ColumnResult(name = "checksheet_id", type = Long.class),
                                @ColumnResult(name = "shift", type = String.class),
                                @ColumnResult(name = "started_at", type = Date.class),
                                @ColumnResult(name = "submitted_at", type = Date.class),
                                @ColumnResult(name = "submission_version", type = Byte.class),
                        }
                )
        ),
})
@NoArgsConstructor
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "inspections", indexes = {
        @Index(name = "idx_inspections_submitted_at", columnList = "submitted_at"),
})
public class Inspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Discriminator: 'AUDIT' (original audit visit) or 'INTERVENTION' (re-inspection wave). */
    @Column(name = "kind", nullable = false, columnDefinition = "varchar")
    private String kind;

    /** Set when kind=AUDIT, NULL when kind=INTERVENTION. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audit_id", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_audit_id"))
    private Audit audit;

    /** Set when kind=INTERVENTION, NULL when kind=AUDIT. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intervention_id", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_intervention_id"))
    private Intervention intervention;

    /** Where the inspection happens. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auditee_location_id", nullable = false, referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_auditee_location_id"))
    private AuditeeLocation auditeeLocation;

    /** The auditor performing this inspection. Nullable until assigned. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_user_id", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_operator_user_id"))
    private User operatorUser;

    /** Dealer Principal for this dealership, drives "My Plans" scope and ack permission.
     *  PRD §2.3 (Owner: Dealer Principal). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_principal_user_id", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_dealer_principal"))
    private User dealerPrincipalUser;

    /** Regional head for this dealership. Nullable. PRD §2.3 (Oversight). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_owner_user_id", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_region_owner"))
    private User regionOwnerUser;

    /** Template binding — direct FK so legacy frequency_of_freq_of_chk_cnt
     *  semantics from the per-shift checksheet model still work. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_checksheet_id"))
    private Checksheet checksheet;

    @Column(name = "shift", columnDefinition = "varchar")
    private String shift;

    @Column(name = "frequency_of_freq_of_chk_cnt")
    private Short frequencyOfFreqOfChkCnt;

    /** Intervention-only payload (NULL for kind=AUDIT). Snapshotted at activation. */
    @Column(name = "priority", columnDefinition = "varchar")
    private String priority;

    @Temporal(TemporalType.DATE)
    @Column(name = "target_date")
    private Date targetDate;

    /** ASSIGNED | IN_PROGRESS | SUBMITTED | VALIDATED | APPROVED | DECLINED. */
    @Column(name = "status", nullable = false, columnDefinition = "varchar")
    private String status;

    // KNOWN PRE-V1.28 type mismatches (filed: M-TYPES-001):
    //  - submissionVersion is Byte (8-bit) but the column is SMALLINT (16-bit).
    //    Silent truncation > 127. Unlikely in practice but a correctness risk.
    //  - waitingUserIds is List<Long> but the column is INTEGER[]. Hibernate
    //    auto-converts; user ids fit in 32 bits today. Future-proof by ALTER
    //    COLUMN to BIGINT[] + List<Long>.
    // Inherited from the legacy UC entity. Keeping as-is in this PR (V1.28
    // scope is the schema collapse; type widening cascades through 5+ services
    // and DTOs and warrants its own PR).
    @Column(name = "submission_version", columnDefinition = "smallint not null default 0")
    private Byte submissionVersion = 0;

    @Column(name = "waiting_user_ids", columnDefinition = "integer[]")
    private List<Long> waitingUserIds = new ArrayList<>();

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "started_at", length = 29)
    private Date startedAt;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "submitted_at", length = 29)
    private Date submittedAt;

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

    /** When the dealer principal acknowledged this plan (kind=INTERVENTION
     *  inspections only — null for kind=AUDIT). Soft signal: doesn't advance
     *  status. V1.30. */
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "acknowledged_at", length = 29)
    private Date acknowledgedAt;

    /** The dealer principal user who acked. Service enforces that this
     *  matches `dealerPrincipalUser.id` at write time. V1.30. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acknowledged_by", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_acknowledged_by"))
    private User acknowledgedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_created_by"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_updated_by"))
    private User updatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id",
                foreignKey = @ForeignKey(name = "fk_inspections_deleted_by"))
    private User deletedBy;
}
