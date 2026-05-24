package com.checkSheet.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Date;

@NoArgsConstructor
@Data
@Entity
@Getter
@Setter
@Table(name = "refresh_token", uniqueConstraints={
        @UniqueConstraint( name = "uk_refresh_token_token",  columnNames ={"token"}),
        @UniqueConstraint( name = "uk_refresh_token_user_device", columnNames = {"user_id", "device_type"}),
}, indexes = {
        @Index(name = "idx_refresh_token_token", columnList = "token"),
})
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** ManyToOne — a user can have one row per device_type. The old @OneToOne
     *  declaration created an implicit UNIQUE(user_id), which prevented a user
     *  from logging in via WEB after they'd logged in via APP (or vice versa).
     *  See V1.29 for the schema-side drop of the auto-generated constraint. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_refresh_token_user_id_users_id"))
    private User user;

    @Column(name = "token", nullable = false)
    private String token;

    @Column(name = "device_type", columnDefinition = "varchar(8)")
    private String deviceType;

    @Column(name = "expiry_date", nullable = false)
    private Instant expiryDate;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Date createdAt = new Date();

}
