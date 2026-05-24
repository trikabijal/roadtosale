package com.checkSheet.entity;

import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.ChksHeaderDTO;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChksHeaderByChecksheetId",
                classes = @ConstructorResult(
                        targetClass = ChksHeaderDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "checksheet_id", type = Long.class),
                                @ColumnResult(name = "chks_header_id", type = Long.class),
                                @ColumnResult(name = "is_result_column", type = Boolean.class),
                                @ColumnResult(name = "is_traceable", type = Boolean.class),
                                @ColumnResult(name = "summary_report_level", type = Byte.class),
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
@Table(name = "chks_headers")
public class ChksHeader {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_headers_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Column(name = "name", nullable = false, columnDefinition = "varchar ")
    private String name;

    @Column(name = "is_traceable", columnDefinition = "bool")
    private Boolean isTraceable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_header_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_headers_chks_header_id_chks_headers_id"))
    private ChksHeader chksHeader;

    @Column(name = "is_result_column", columnDefinition = "bool default false")
    private Boolean isResultColumn = false;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_headers_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_headers_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_headers_updated_by_user_id"))
    private User updatedBy;

    @Column(name = "summary_report_level")
    private Byte summaryReportLevel;
}