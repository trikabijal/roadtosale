package com.checkSheet.entity;

import com.checkSheet.DTO.ChecksheetApprovalHistoryDTO;
import com.checkSheet.DTO.ChecksheetValidationHistoryDTO;
import com.checkSheet.constant.ChecksheetApprovalStatusType;
import com.checkSheet.constant.ChecksheetValidationStatusType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChecksheetApproverHistoryByChecksheetId",
                classes = @ConstructorResult(
                        targetClass = ChecksheetApprovalHistoryDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "remarks", type = String.class),
                                @ColumnResult(name = "status", type = ChecksheetApprovalStatusType.class),
                                @ColumnResult(name = "approver_user_id", type = Long.class),
                                @ColumnResult(name = "approved_at", type = Date.class),
                                @ColumnResult(name = "version", type = Long.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class)
                        }
                )
        ),
})
@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "checksheet_approvals_history")
public class ChecksheetApprovalHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_approvals_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Column(name = "version", nullable = false, columnDefinition = "bigint not null ")
    private Long version;

    @Column(name = "status", nullable = false, columnDefinition = "varchar not null")
    @Enumerated(EnumType.STRING)
    private ChecksheetApprovalStatusType status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approver_user_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_approvals_approver_user_id_user_id"))
    private User approverUserId;

    @Column(name = "approved_at", length = 29)
    private Date approvedAt;

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();
}