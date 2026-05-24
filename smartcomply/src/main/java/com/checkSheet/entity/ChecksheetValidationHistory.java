package com.checkSheet.entity;

import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.ChecksheetValidationHistoryDTO;
import com.checkSheet.constant.ChecksheetValidationStatusType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChecksheetValidatorHistoryByChecksheetId",
                classes = @ConstructorResult(
                        targetClass = ChecksheetValidationHistoryDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "remarks", type = String.class),
                                @ColumnResult(name = "status", type = ChecksheetValidationStatusType.class),
                                @ColumnResult(name = "validator_user_id", type = Long.class),
                                @ColumnResult(name = "validated_at", type = Date.class),
                                @ColumnResult(name = "version", type = Long.class),
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
@Table(name = "checksheet_validations_history")
public class ChecksheetValidationHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_validations_history_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Column(name = "version", nullable = false, columnDefinition = "bigint not null ")
    private Long version;

    @Column(name = "status", nullable = false, columnDefinition = "varchar not null")
    @Enumerated(EnumType.STRING)
    private ChecksheetValidationStatusType status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "validator_user_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheet_validations_history_validator_user_id_user_id"))
    private User validatorUserId;

    @Column(name = "validated_at", length = 29)
    private Date validatedAt;

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

}