package com.checkSheet.entity;

import com.checkSheet.DTO.ChecksheetApprovalHistoryDTO;
import com.checkSheet.DTO.UserChecksheetApprovalHistoryDTO;
import com.checkSheet.DTO.UserChecksheetValidationHistoryDTO;
import com.checkSheet.constant.ChecksheetApprovalStatusType;
import com.checkSheet.constant.ChecksheetDataApprovalStatusType;
import com.checkSheet.constant.ChecksheetDataValidationStatusType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getUserChecksheetApproverHistoryByInspectionId",
                classes = @ConstructorResult(
                        targetClass = UserChecksheetApprovalHistoryDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "remarks", type = String.class),
                                @ColumnResult(name = "status", type = ChecksheetDataApprovalStatusType.class),
                                @ColumnResult(name = "data_approver_user_id", type = Long.class),
                                @ColumnResult(name = "approved_at", type = Date.class),
                                @ColumnResult(name = "version", type = Byte.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class)
                        }
                )
        ),@SqlResultSetMapping(
            name = "getUsrChksApprovalhistory",
            classes = @ConstructorResult(
                    targetClass = UserChecksheetApprovalHistoryDTO.class,
                    columns = {
                            @ColumnResult(name = "id", type = Long.class),
                            @ColumnResult(name = "inspection_id", type = Long.class),
                            @ColumnResult(name = "remarks", type = String.class),
                            @ColumnResult(name = "status", type = ChecksheetDataApprovalStatusType.class),
                            @ColumnResult(name = "approved_at", type = Date.class),
                            @ColumnResult(name = "version", type = Byte.class),
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
@Table(name = "user_checksheet_approvals_history")
public class UserChecksheetApprovalHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_approvals_history_user_checksheet_id_user_checksheets_id"))
    private com.checkSheet.entity.Inspection inspection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_approvals_history_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Column(name = "version", nullable = false, columnDefinition = "smallint not null ")
    private Byte version;

    @Column(name = "status", nullable = false, columnDefinition = "varchar not null")
    @Enumerated(EnumType.STRING)
    private ChecksheetDataApprovalStatusType status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_approver_user_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_approvals_history_data_approver_user_id_user_id"))
    private User dataApproverUserId;

    @Column(name = "approved_at", length = 29)
    private Date approvedAt;

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();
}