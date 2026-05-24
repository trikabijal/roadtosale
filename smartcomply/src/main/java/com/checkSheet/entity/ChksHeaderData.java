package com.checkSheet.entity;

import java.util.Date;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import com.checkSheet.DTO.ChksHeaderDataDTO;
import com.checkSheet.DTO.DashboardDTO;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChksHeaderData",
                classes = @ConstructorResult(
                        targetClass = ChksHeaderDataDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "description", type = String.class),
                                @ColumnResult(name = "checksheet_id", type = Long.class),
                                @ColumnResult(name = "chks_header_id", type = Long.class),
                                @ColumnResult(name = "chks_header_data_id", type = Long.class)
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getChksHeaderDataByChksId",
                classes = @ConstructorResult(
                        targetClass = ChksHeaderDataDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "description", type = String.class),
                                @ColumnResult(name = "checksheet_id", type = Long.class),
                                @ColumnResult(name = "chks_header_id", type = Long.class),
                                @ColumnResult(name = "chks_header_data_id", type = Long.class),
                                @ColumnResult(name = "level", type = Long.class),
                                @ColumnResult(name = "order_no", type = Integer.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getRespectedChecksheetHeaderData",
                classes = @ConstructorResult(
                        targetClass = DashboardDTO.class,
                        columns = {
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "chks_header_id", type = Long.class),
                                @ColumnResult(name = "chks_header_data_id", type = Long.class),
                                @ColumnResult(name = "level", type = Long.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getRespectedChecksheetQuestionsData",
                classes = @ConstructorResult(
                        targetClass = DashboardDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "chks_header_data_id", type = Long.class),
                        }
                )
        )
})
@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "chks_header_data", indexes = {
        @Index(name = "idx_chks_header_data_checksheet_id", columnList = "checksheet_id"),
        @Index(name = "idx_chks_header_data_chks_header_id", columnList = "chks_header_id"),
        @Index(name = "idx_chks_header_data_chks_header_data_id", columnList = "chks_header_data_id"),
        @Index(name = "idx_chks_header_data_order_no", columnList = "order_no"),
})
public class ChksHeaderData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_header_data_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_header_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_header_data_chks_header_id_chks_headers_id"))
    private ChksHeader chksHeader;

    @Column(name = "name", nullable = false, columnDefinition = "varchar ")
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_header_data_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_header_data_chks_header_data_chks_header_data_id"))
    private ChksHeaderData chksHeaderData;

    @Column(name = "level")
    private Long level;

    @Column(name = "order_no")
    private Integer orderNo;

    @Column(name = "description", columnDefinition = "text")
    private String description;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_header_data_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_header_data_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_header_data_updated_by_user_id"))
    private User updatedBy;

    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    public void setDescription(String description) {
        this.description = description != null ? description.trim() : null;
    }

}