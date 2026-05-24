package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;

@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "auditee_types", uniqueConstraints = {
        @UniqueConstraint(name = "uk_auditee_types_code", columnNames = {"code"})
})
public class AuditeeType implements java.io.Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

    @Column(name = "label", nullable = false, length = 30, columnDefinition = "varchar(30)")
    private String label;

    @Column(name = "code", nullable = false, length = 5, columnDefinition = "varchar(5)")
    private String code;
}
