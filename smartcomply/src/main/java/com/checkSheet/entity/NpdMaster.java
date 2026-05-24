package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "npd_master",
uniqueConstraints = {
    @UniqueConstraint(name = "uq_npd_master_checksheet_id_npd_date_shift", columnNames = {"checksheet_id", "npd_date", "shift"})
}, indexes = {
    @Index(name = "idx_npd_master_checksheet_id", columnList = "checksheet_id"),
    @Index(name = "idx_npd_master_npd_date", columnList = "npd_date"),
    @Index(name = "idx_npd_master_shift", columnList = "shift"),
    @Index(name = "idx_npd_master_is_exception", columnList = "is_exception"),
    @Index(name = "idx_npd_master_created_by", columnList = "created_by"),
})
public class NpdMaster implements java.io.Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checksheet_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_npd_master_checksheet_id_checksheets_id"))
    private Checksheet checksheet;

    @Temporal(TemporalType.DATE)
    @Column(name = "npd_date", nullable = false, length = 10, columnDefinition = "DATE DEFAULT CURRENT_DATE")
    private Date npdDate;

    @Column(name = "shift", length = 16)
    private String shift;

    @Column(name = "is_exception", nullable = false, columnDefinition = "bool default false")
    private Boolean isException = false;

    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_npd_master_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_npd_master_updated_by_user_id"))
    private User updatedBy;

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at", length = 29, columnDefinition = "TIMESTAMP")
    private Date updatedAt;

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = new Date();
    }
}


