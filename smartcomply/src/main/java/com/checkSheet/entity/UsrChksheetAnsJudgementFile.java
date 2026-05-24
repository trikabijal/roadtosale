package com.checkSheet.entity;

import com.checkSheet.DTO.UsrChecksheetAnsJudgementDTO;
import com.checkSheet.DTO.UsrChksheetAnsJudgementFileDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getUsrChecksheetAnsJudgementFiles",
                classes = @ConstructorResult(
                        targetClass = UsrChksheetAnsJudgementFileDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "inspection_id", type = Long.class),
                                @ColumnResult(name = "usr_chksheet_ans_judgement_id", type = Long.class),
                                @ColumnResult(name = "path", type = String.class)
                        }
                )
        ),
})
@Getter
@Setter
@Entity
@Table(name = "usr_chksheet_ans_judgement_files")
public class UsrChksheetAnsJudgementFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inspection_id", nullable = false, referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgement_files_user_checksheets_id"))
    private com.checkSheet.entity.Inspection inspection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usr_chksheet_ans_judgement_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "usr_chksheet_ans_judgement_files_fk_usr_chksheet_ans_judgement_id"))
    private UsrChksheetAnsJudgement usrChksheetAnsJudgement;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgement_files_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgement_files_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_usr_chksheet_ans_judgement_files_updated_by_user_id"))
    private User updatedBy;

}
