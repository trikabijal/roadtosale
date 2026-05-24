package com.checkSheet.entity;

import com.checkSheet.DTO.AppVersionDTO;
import com.checkSheet.DTO.ChecksheetDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import java.util.Date;

@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "app_versions", uniqueConstraints={
        @UniqueConstraint( name = "uk_app_versions_version_os",  columnNames ={"version", "os"})
})
@SqlResultSetMappings({
    @SqlResultSetMapping(
        name = "getAppVersionByOs",
        classes = @ConstructorResult(
                targetClass = AppVersionDTO.class,
                columns = {
                        @ColumnResult(name = "id", type = Long.class),
                        @ColumnResult(name = "version", type = Long.class),
                        @ColumnResult(name = "url", type = String.class),
                        @ColumnResult(name = "os", type = String.class),
                        @ColumnResult(name = "is_forcefully_update", type = Boolean.class),
                        @ColumnResult(name = "version_name", type = String.class),
                }
        )
    )
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppVersion implements java.io.Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "version")
    private Long version;

    @Column(name = "url", columnDefinition = "varchar(512)")
    private String url;

    @Column(name = "os")
    private String os;

    @Column(name = "version_name")
    private String versionName;

    @Column(name = "is_forcefully_update", columnDefinition = "bool default false")
    private Boolean isForcefullyUpdate = false;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    protected Date createdAt = new Date();

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "updated_at", length = 29, columnDefinition = "TIMESTAMP")
    protected Date updatedAt;

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Date();
    }
}
