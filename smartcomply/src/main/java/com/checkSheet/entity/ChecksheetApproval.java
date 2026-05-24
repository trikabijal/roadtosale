package com.checkSheet.entity;

import com.checkSheet.constant.ChecksheetApprovalStatusType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "checksheet_approvals")
public class ChecksheetApproval {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_approvals_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

//    @Column(name = "revision")
//    private Long revision;

    @Column(name = "status", nullable = false, columnDefinition = "varchar not null")
    @Enumerated(EnumType.STRING)
    private ChecksheetApprovalStatusType status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approver_user_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_approvals_approver_user_id_user_id"))
    private User approverUserId;

//    @Column(name = "validated_at", length = 29)
//    private Date validatedAt;

    @Column(name = "approved_at", length = 29)
    private Date approvedAt;

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_approvals_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_approvals_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_approvals_updated_by_user_id"))
    private User updatedBy;

}