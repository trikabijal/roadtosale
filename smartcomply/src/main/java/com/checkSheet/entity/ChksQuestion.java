package com.checkSheet.entity;

import com.checkSheet.DTO.ChksHeaderDTO;
import com.checkSheet.DTO.ChksQuestionDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChksQuestionByChecksheetHeaderDataId",
                classes = @ConstructorResult(
                        targetClass = ChksQuestionDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "description", type = String.class),
                                @ColumnResult(name = "order_no", type = Integer.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getChksQuestionByChecksheetId",
                classes = @ConstructorResult(
                        targetClass = ChksQuestionDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "description", type = String.class),
                                @ColumnResult(name = "chks_header_id", type = Long.class),
                                @ColumnResult(name = "chks_header_data_id", type = Long.class),
                                @ColumnResult(name = "order_no", type = Integer.class),
                        }
                )
        ),
})
@Getter
@Setter
@Entity
@Table(name = "chks_questions", indexes = {
        @Index(name = "idx_chks_questions_checksheet_id", columnList = "checksheet_id"),
        @Index(name = "idx_chks_questions_chks_header_id", columnList = "chks_header_id"),
        @Index(name = "idx_chks_questions_chks_header_data_id", columnList = "chks_header_data_id"),
        @Index(name = "idx_chks_questions_order_no", columnList = "order_no"),
})
public class ChksQuestion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_questions_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chks_header_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_questions_chks_header_id_chks_headers_id"))
    private ChksHeader chksHeader;

    @Column(name = "name", nullable = false, columnDefinition = "varchar ")
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_header_data_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_questions_chks_header_data_id_chks_header_data_id"))
    private ChksHeaderData chksHeaderData;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "order_no")
    private Integer orderNo;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_questions_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_questions_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_questions_updated_by_user_id"))
    private User updatedBy;

    public void setName(String name) {
        this.name = name != null ? name.trim() : null;
    }

    public void setDescription(String description) {
        this.description = description != null ? description.trim() : null;
    }

}