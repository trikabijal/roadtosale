package com.checkSheet.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Index;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@Getter
@Setter
@Entity
@Table(
    name = "user_checksheet_answer_files",
    indexes = {
        @Index(
            name = "idx_ucaf_answer_deleted_id",
            columnList = "user_checksheet_answer_id, deleted_at, id"
        ),
        @Index(
            name = "idx_ucaf_answer_path_deleted",
            columnList = "user_checksheet_answer_id, path, deleted_at"
        ),
        @Index(
            name = "idx_ucaf_answer_deleted_created_at",
            columnList = "user_checksheet_answer_id, deleted_at, created_at"
        ),
        @Index(name = "idx_ucaf_created_by", columnList = "created_by"),
        @Index(name = "idx_ucaf_updated_by", columnList = "updated_by"),
        @Index(name = "idx_ucaf_deleted_by", columnList = "deleted_by")
    }
)
public class UserChecksheetAnswerFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
        name = "user_checksheet_answer_id",
        nullable = false,
        referencedColumnName = "id",
        foreignKey = @ForeignKey(name = "fk_user_checksheet_answer_files_user_checksheet_answers_id")
    )
    private UserChecksheetAnswer userChecksheetAnswer;

    @Column(name = "mime_type", columnDefinition = "varchar(150)")
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "file_hash_sha256", columnDefinition = "varchar(64)")
    private String fileHashSha256;

    @Column(name = "path", columnDefinition = "text")
    private String path;

    @Column(name = "thumbnail_path", columnDefinition = "text")
    private String thumbnailPath;

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
    @JoinColumn(
        name = "created_by",
        referencedColumnName = "id",
        foreignKey = @ForeignKey(name = "fk_user_checksheet_answer_files_created_by_user_id")
    )
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "deleted_by",
        referencedColumnName = "id",
        foreignKey = @ForeignKey(name = "fk_user_checksheet_answer_files_deleted_by_user_id")
    )
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "updated_by",
        referencedColumnName = "id",
        foreignKey = @ForeignKey(name = "fk_user_checksheet_answer_files_updated_by_user_id")
    )
    private User updatedBy;
}
