package com.checkSheet.entity;

import com.checkSheet.DTO.DepartmentDTO;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;

import java.util.Date;
@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "searchUniqueUsers",
                classes = @ConstructorResult(
                        targetClass = DepartmentDTO.class,
                        columns = {
                                @ColumnResult(name = "user_id", type = Long.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class),
                                @ColumnResult(name = "email", type = String.class),
                                @ColumnResult(name = "username", type = String.class),
                                @ColumnResult(name = "role_ids_str", type = String.class),
                                @ColumnResult(name = "role_name", type = String.class),
                                @ColumnResult(name = "role_code", type = String.class),
                                @ColumnResult(name = "dept_ids_str", type = String.class),
                                @ColumnResult(name = "dept_name", type = String.class),
                                @ColumnResult(name = "sect_ids_str", type = String.class),
                                @ColumnResult(name = "sect_name", type = String.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "searchDepartments",
                classes = @ConstructorResult(
                        targetClass = DepartmentDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "user_id", type = Long.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class),
                                @ColumnResult(name = "email", type = String.class),
                                @ColumnResult(name = "username", type = String.class),
                                @ColumnResult(name = "role_id", type = Long.class),
                                @ColumnResult(name = "role_name", type = String.class),
                                @ColumnResult(name = "role_code", type = String.class),
                                @ColumnResult(name = "created_at", type = Date.class),
                                @ColumnResult(name = "urd_id", type = Long.class),
                                @ColumnResult(name = "dept_name", type = String.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "searchDepartmentsForDownload",
                classes = @ConstructorResult(
                        targetClass = DepartmentDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "user_id", type = Long.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class),
                                @ColumnResult(name = "email", type = String.class),
                                @ColumnResult(name = "username", type = String.class),
                                @ColumnResult(name = "role_id", type = Long.class),
                                @ColumnResult(name = "role_name", type = String.class),
                                @ColumnResult(name = "role_code", type = String.class),
                        }
                )
        ),
        @SqlResultSetMapping(
                name = "getAllDepartments",
                classes = @ConstructorResult(
                        targetClass = DepartmentDTO.class,
                        columns = {
                                @ColumnResult(name = "id", type = Long.class),
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "user_id", type = Long.class),
                                @ColumnResult(name = "first_name", type = String.class),
                                @ColumnResult(name = "last_name", type = String.class),
                                @ColumnResult(name = "email", type = String.class),
                                @ColumnResult(name = "username", type = String.class),
                                @ColumnResult(name = "created_at", type = Date.class),
                                @ColumnResult(name = "department_id", type = Long.class),
                        }
                )
        ),
})
@NoArgsConstructor
@Data
@Getter
@Setter
@Entity
@Table(name = "departments", uniqueConstraints={
        @UniqueConstraint( name = "departments_uk_name_department_id",  columnNames ={"name","department_id"})
})
public class Department {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_departments_department_id_departments_id"))
    private Department departmentId;

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
    @JoinColumn(name = "created_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_departments_created_by_user_id"))
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deleted_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_departments_deleted_by_user_id"))
    private User deletedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", referencedColumnName = "id", foreignKey = @ForeignKey(name = "fk_departments_updated_by_user_id"))
    private User updatedBy;
}