package com.checkSheet.entity;

import com.checkSheet.DTO.UserChecksheetTraceValueDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getUserChksTraceValues",
                classes = @ConstructorResult(
                        targetClass = UserChecksheetTraceValueDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "inspection_id", type = Long.class),
                                @ColumnResult(name = "chks_header_data_id", type = Long.class),
                                @ColumnResult(name = "trace_value", type = String.class),
                        }
                )
        )
})
@Getter
@Setter
@Entity
@Table(
    name = "user_checksheet_trace_values",
    uniqueConstraints={
        @UniqueConstraint( name = "uk_user_checksheet_trace_values_inspection_id", columnNames ={"inspection_id","chks_header_data_id"})
    }
)
public class UserChecksheetTraceValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "user_checksheet_trace_values_fk_user_checksheets_id"))
    private com.checkSheet.entity.Inspection inspection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_header_data_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "user_checksheet_trace_values_fk_chks_header_data_id"))
    private ChksHeaderData chksHeaderData;

    @Column(name = "trace_value", nullable = false, columnDefinition = "varchar ")
    private String traceValue;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_user_checksheet_answers_updated_by_user_id"))
    private User updatedBy;
}
