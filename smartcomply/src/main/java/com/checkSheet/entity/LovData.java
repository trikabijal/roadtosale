package com.checkSheet.entity;

import com.checkSheet.DTO.LovDataDTO;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.*;

@SqlResultSetMappings({
        @SqlResultSetMapping(
                name = "getLovData",
                classes = @ConstructorResult(
                        targetClass = LovDataDTO.class,
                        columns = {
                                @ColumnResult(name = "name", type = String.class),
                                @ColumnResult(name = "value", type = String.class),
                                @ColumnResult(name = "value_type", type = String.class),
                        }
                )
        ),
})
@NoArgsConstructor
@Data
@Getter
@Setter
@AllArgsConstructor
@Entity
@Table(name = "lov_data")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LovData implements java.io.Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false,columnDefinition = "varchar")
    private String name;

    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    @Column(name = "value_type", nullable = false, columnDefinition = "varchar")
    private String valueType;
}
