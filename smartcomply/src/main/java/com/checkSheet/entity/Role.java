package com.checkSheet.entity;

import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.RoleDTO;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Date;
import java.util.List;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getAllRoles",
                classes = @ConstructorResult(
                        targetClass = RoleDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "role_code", type = String.class),
                        }
                )
        ),
})
@Getter
@Setter
@Entity
@Table(name = "roles", uniqueConstraints={
        @UniqueConstraint( name = "uk_roles_role_code",  columnNames ={"role_code"})
})
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "role_code", columnDefinition = "varchar ")
    private String roleCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_roles_role_id_roles_id"))
    private Role role;

    // New parent link mapped to the new column
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "parent_role_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_roles_parent_role_id_roles_id"))
        private Role parentRole;

    @Column(name = "name", columnDefinition = "varchar ")
    private String name;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_roles_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_roles_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_roles_updated_by_user_id"))
    private User updatedBy;

    // Relationship to permissions through RolePermission
    @OneToMany(mappedBy = "role", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<RolePermission> rolePermissions;

}