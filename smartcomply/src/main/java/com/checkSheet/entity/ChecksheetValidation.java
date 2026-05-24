package com.checkSheet.entity;

import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.RoleDTO;
import com.checkSheet.constant.ChecksheetValidationStatusType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChecksheetValidatorByChecksheetId",
                classes = @ConstructorResult(
                        targetClass = ChecksheetValidationDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "remarks", type = String.class),
                                @ColumnResult(name = "status", type = ChecksheetValidationStatusType.class),
                                @ColumnResult(name = "validator_user_id", type = Long.class),
                                @ColumnResult(name = "validated_at", type = Date.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class),
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
@Table(name = "checksheet_validations")
public class ChecksheetValidation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_validations_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

//    @Column(name = "revision")
//    private Long revision;

    @Column(name = "status", nullable = false, columnDefinition = "varchar not null")
    @Enumerated(EnumType.STRING)
    private ChecksheetValidationStatusType status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "validator_user_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_validations_validator_user_id_user_id"))
    private User validatorUserId;

//    @Column(name = "submitted_at", length = 29)
//    private Date submittedAt;

    @Column(name = "validated_at", length = 29)
    private Date validatedAt;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_validations_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_validations_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_validations_updated_by_user_id"))
    private User updatedBy;

}