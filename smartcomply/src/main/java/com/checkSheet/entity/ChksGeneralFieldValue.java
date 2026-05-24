package com.checkSheet.entity;

import com.checkSheet.DTO.ChksGeneralFieldValueDTO;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getUserChksGeneralFieldValues",
                classes = @ConstructorResult(
                        targetClass = ChksGeneralFieldValueDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "inspection_id", type = Long.class),
                                @ColumnResult(name = "chks_general_field_id", type = Long.class),
                                @ColumnResult(name = "value", type = String.class)
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
@Table(name = "chks_general_field_values")
public class ChksGeneralFieldValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

//    @ManyToOne(fetch = FetchType.LAZY, optional = false)
//    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_field_value_checksheet_id_checksheets_id"))
//    private Checksheet checksheetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_field_value_user_checksheet_id_user_checksheets_id"))
    private com.checkSheet.entity.Inspection inspection;

    @Column(name = "value", columnDefinition = "varchar ")
    private String value;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_general_field_id", nullable = false, foreignKey = @ForeignKey(name = "fk_chks_general_field_value_chks_general_field_id_chks_general_fields_id"))
    private ChksGeneralField chksGeneralField;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_field_value_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_field_value_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_field_value_updated_by_user_id"))
    private User updatedBy;

}