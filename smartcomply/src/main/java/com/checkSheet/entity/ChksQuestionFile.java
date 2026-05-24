package com.checkSheet.entity;

import com.checkSheet.DTO.ChksHeaderDataFileDTO;
import com.checkSheet.DTO.ChksQuestionFileDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChksQuestionFileDTO",
                classes = @ConstructorResult(
                        targetClass = ChksQuestionFileDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "path", type = String.class),
                                @ColumnResult(name = "chks_question_id", type = Long.class),
                        }
                )
        ),
})
@Getter
@Setter
@Entity
@Table(name = "chks_question_files")
public class ChksQuestionFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_files_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chks_question_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_files_chks_question_id_chks_questions_id"))
    private ChksQuestion chksQuestion;

    @Column(name = "path", columnDefinition = "varchar ")
    private String path;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_files_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_files_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_chks_question_files_updated_by_user_id"))
    private User updatedBy;

}