package com.checkSheet.entity;

import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.RoleDTO;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChksGeneralFieldByChecksheetId",
                classes = @ConstructorResult(
                        targetClass = ChksGeneralFieldDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "checksheet_id", type = Long.class),
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
@Table(name = "chks_general_fields")
public class ChksGeneralField {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_fields_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Column(name = "name", nullable = false, columnDefinition = "varchar ")
    private String name;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_fields_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_fields_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_general_fields_updated_by_user_id"))
    private User updatedBy;


}