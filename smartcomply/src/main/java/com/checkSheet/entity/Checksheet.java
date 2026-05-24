package com.checkSheet.entity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import com.checkSheet.DTO.ChecksheetDTO;
import com.checkSheet.DTO.DashboardDTO;
import com.checkSheet.constant.ChecksheetFrequencyType;
import com.checkSheet.constant.ChecksheetStatusType;
import com.checkSheet.constant.ChecksheetType;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.persistence.Column;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.SqlResultSetMappings;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getChecksheets",
                classes = @ConstructorResult(
                        targetClass = ChecksheetDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getRespectedChecksheet",
                classes = @ConstructorResult(
                        targetClass = ChecksheetDTO.class,
                        columns = {
                        @ColumnResult(name = "id", type = Long.class),
                        @ColumnResult(name = "name", type = String.class),
                        @ColumnResult(name = "approver_user_ids", type = String.class),
                        @ColumnResult(name = "created_at", type = Date.class),
                        @ColumnResult(name = "data_approver_user_ids", type = String.class),
                        @ColumnResult(name = "data_validator_user_ids", type = String.class),
                        @ColumnResult(name = "description", type = String.class),
                        @ColumnResult(name = "implementation_date", type = Date.class),
                        @ColumnResult(name = "expiry_date", type = Date.class),
                        @ColumnResult(name = "model_no", type = String.class),
                        @ColumnResult(name = "version", type = Long.class),
                        @ColumnResult(name = "serial_number", type = String.class),
                        @ColumnResult(name = "status", type = String.class),
                        @ColumnResult(name = "validator_user_ids", type = String.class),
                        @ColumnResult(name = "preparer_user_id", type = Long.class),
                        @ColumnResult(name = "escalation_guidelines_days", type = Long.class),
                        @ColumnResult(name = "alert_to_user_ids", type = String.class),
                        @ColumnResult(name = "escalate_to_user_ids", type = String.class),
                        @ColumnResult(name = "frequency_of_check", type = String.class),
                        @ColumnResult(name = "uid", type = String.class),
                        @ColumnResult(name = "operator_user_ids", type = String.class),
                        @ColumnResult(name = "is_file_upload", type = Boolean.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getRespectedUserChecksheet",
                classes = @ConstructorResult(
                        targetClass = ChecksheetDTO.class,
                        columns = {
                            @ColumnResult(name = "id", type = Long.class),
                            @ColumnResult(name = "name", type = String.class),
                            @ColumnResult(name = "approver_user_ids", type = String.class),
                            @ColumnResult(name = "created_at", type = Date.class),
                            @ColumnResult(name = "data_approver_user_ids", type = String.class),
                            @ColumnResult(name = "data_validator_user_ids", type = String.class),
                            @ColumnResult(name = "description", type = String.class),
                            @ColumnResult(name = "implementation_date", type = Date.class),
                            @ColumnResult(name = "model_no", type = String.class),
                            @ColumnResult(name = "version", type = Long.class),
                            @ColumnResult(name = "serial_number", type = String.class),
                            @ColumnResult(name = "status", type = String.class),
                            @ColumnResult(name = "validator_user_ids", type = String.class),
                            @ColumnResult(name = "preparer_user_id", type = Long.class),
                            @ColumnResult(name = "escalation_guidelines_days", type = Long.class),
                            @ColumnResult(name = "alert_to_user_ids", type = String.class),
                            @ColumnResult(name = "escalate_to_user_ids", type = String.class),
                            @ColumnResult(name = "frequency_of_check", type = String.class),
                            @ColumnResult(name = "uid", type = String.class),
                            @ColumnResult(name = "operator_user_ids", type = String.class),
                            @ColumnResult(name = "is_file_upload", type = Boolean.class),
                            @ColumnResult(name = "operator_id", type = Long.class),
                            @ColumnResult(name = "operator_first_name", type = String.class),
                            @ColumnResult(name = "operator_last_name", type = String.class),
                            @ColumnResult(name = "operator_username", type = String.class),
                            @ColumnResult(name = "started_at", type = Date.class),
                            @ColumnResult(name = "submitted_at", type = Date.class),
                            @ColumnResult(name = "shift", type = String.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getRespectedChecksheetDetail",
                classes = @ConstructorResult(
                        targetClass = ChecksheetDTO.class,
                        columns = {
                        @ColumnResult(name = "id", type = Long.class),
                        @ColumnResult(name = "name", type = String.class),
                        @ColumnResult(name = "approver_user_ids", type = String.class),
                        @ColumnResult(name = "created_at", type = Date.class),
                        @ColumnResult(name = "data_approver_user_ids", type = String.class),
                        @ColumnResult(name = "data_validator_user_ids", type = String.class),
                        @ColumnResult(name = "description", type = String.class),
                        @ColumnResult(name = "implementation_date", type = Date.class),
                        @ColumnResult(name = "expiry_date", type = Date.class),
                        @ColumnResult(name = "model_no", type = String.class),
                        @ColumnResult(name = "version", type = Long.class),
                        @ColumnResult(name = "serial_number", type = String.class),
                        @ColumnResult(name = "status", type = String.class),
                        @ColumnResult(name = "validator_user_ids", type = String.class),
                        @ColumnResult(name = "preparer_user_id", type = Long.class),
                        @ColumnResult(name = "escalation_guidelines_days", type = Long.class),
                        @ColumnResult(name = "alert_to_user_ids", type = String.class),
                        @ColumnResult(name = "escalate_to_user_ids", type = String.class),
                        @ColumnResult(name = "frequency_of_check", type = String.class),
                        @ColumnResult(name = "frequency_of_freq_of_chk", type = Short.class),
                        @ColumnResult(name = "operator_user_ids", type = String.class),
                        @ColumnResult(name = "department_id", type = Long.class),
                        @ColumnResult(name = "is_file_upload", type = Boolean.class),
                        @ColumnResult(name = "asset_code", type = String.class),
                        @ColumnResult(name = "checksheet_type", type = ChecksheetType.class),
                        @ColumnResult(name = "uid", type = String.class),
                        @ColumnResult(name = "version_remark", type = String.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getOperatorChecksheet",
                classes = @ConstructorResult(
                        targetClass = ChecksheetDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "model_no", type = String.class),
                                @ColumnResult(name = "frequency_of_check", type = String.class),
                                @ColumnResult(name = "frequency_of_freq_of_chk", type = Short.class),
                                @ColumnResult(name = "uid", type = String.class),
                                @ColumnResult(name = "expiry_date", type = Date.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getDashboardChecksheets",
                classes = @ConstructorResult(
                        targetClass = DashboardDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "version", type = Long.class),
                                @ColumnResult(name = "name", type = String.class)
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getDashboardQuestions",
                classes = @ConstructorResult(
                        targetClass = DashboardDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class)
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getChecksheetSummaryData",
                classes = @ConstructorResult(
                        targetClass = DashboardDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "ok_count", type = Long.class),
                                @ColumnResult(name = "not_ok_count", type = Long.class)
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
@Table(name = "checksheets",
        indexes = {
                @Index(name = "idx_checksheets_frequency_of_check", columnList = "frequency_of_check"),
        })
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Checksheet implements java.io.Serializable {


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "checksheets_fk_checksheet_id"))
    private Checksheet checksheet;

    @Column(name = "uid", columnDefinition = "varchar")
    private String uid;

    @Column(name = "model_no")
    private String modelNo;

    @Column(name = "name", columnDefinition = "varchar ")
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "status", nullable = false, columnDefinition = "varchar not null")
    @Enumerated(EnumType.STRING)
    private ChecksheetStatusType status;

    @Column(name = "checksheet_type", nullable = false, columnDefinition = "varchar default 'PRIVATE'")
    @Enumerated(EnumType.STRING)
    private ChecksheetType checksheetType = ChecksheetType.PRIVATE;

    @Column(name = "validator_user_ids", columnDefinition = "_int8 ")
    private List<Long> validatorUserIds;

    @Column(name = "approver_user_ids", columnDefinition = "_int8 ")
    private List<Long> approverUserIds;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preparer_user_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheets_preparer_user_id_user_id"))
    private User preparerUser;

    @Column(name = "data_validator_user_ids", columnDefinition = "_int8 ")
    private List<Long> dataValidatorUserIds;

    @Column(name = "data_approver_user_ids", columnDefinition = "_int8 ")
    private List<Long> dataApproverUserIds;

    @Column(name = "operator_user_ids", columnDefinition = "_int8 ")
    private List<Long> operatorUserIds;

    @Column(name = "waiting_user_ids", columnDefinition = "integer[]")
    private List<Long> waitingUserIds = new ArrayList<>();

    @Column(name = "escalate_to_user_ids", columnDefinition = "_int8 ")
    private List<Long> escalateToUserIds;

    @Column(name = "alert_to_user_ids", columnDefinition = "_int8 ")
    private List<Long> alertToUserIds;

    @Column(name = "version", columnDefinition = "bigint default 0")
    private Long version = 0L;

    @Column(name = "version_remark", columnDefinition = "varchar")
    private String versionRemark;

    @Column(name = "serial_number", length = 10)
    private String serialNumber;

    @Column(name = "frequency_of_check", columnDefinition = "varchar ")
    @Enumerated(EnumType.STRING)
    private ChecksheetFrequencyType frequencyOfCheck;

    @Column(name = "frequency_of_freq_of_chk")
    private Short frequencyOfFreqOfChk;

    @Column(name = "implementation_date", length = 29)
    private Date implementationDate;

    @Temporal(TemporalType.DATE)
    @Column(name = "expiry_date", length = 10)
    private Date expiryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_to_user_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheets_alert_to_user_id_user_id"))
    private User alertToUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalate_to_user_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheets_escalate_to_user_id_user_id"))
    private User escalateToUserId;

    @Column(name = "escalation_guidelines_days")
    private Long escalationGuidelinesDays;

    @Column(name = "validate_or_approve_version", columnDefinition = "bigint not null default 0")
    private Long validateOrApproveVersion = 0L;

    @Column(name = "submitted_at", length = 29)
    private Date submittedAt;

    @Column(name = "path", columnDefinition = "varchar ")
    private String path;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheets_department_id_departments_id"))
    private Department department;

    @Column(name = "is_file_upload", columnDefinition = "bool")
    private Boolean isFileUpload;

    @Column(name = "asset_code", columnDefinition = "varchar ")
    private String assetCode;

    @Column(name = "npd_day", columnDefinition = "_varchar default '{}'")
    private List<String> npdDay = new ArrayList<>();

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheets_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheets_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_checksheets_updated_by_user_id"))
    private User updatedBy;

//    @OneToMany(fetch = FetchType.LAZY, mappedBy = "checksheet")
//    private List<Checksheet> checksheets = new ArrayList<>(0);

//    @OneToMany(fetch = FetchType.LAZY, mappedBy = "checksheet")
//    private List<Inspection> userChecksheets = new ArrayList<>(0);

//    @OneToMany(fetch = FetchType.LAZY, mappedBy = "checksheet")
//    private List<ChksQuestion> chksQuestions = new ArrayList<>(0);

//    @OneToMany(fetch = FetchType.LAZY, mappedBy = "checksheet")
//    private List<ChksHeader> chksHeaders = new ArrayList<>(0);

//    @OneToMany(fetch = FetchType.LAZY, mappedBy = "checksheet")
//    private List<ChksQuestionResult> chksQuestionResults = new ArrayList<>(0);

}
