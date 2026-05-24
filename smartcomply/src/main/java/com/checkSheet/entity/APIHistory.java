package com.checkSheet.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@Table(name = "api_history", indexes = {
        @Index(name = "idx_api_history_created_at", columnList = "created_at"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public class APIHistory implements java.io.Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "request", columnDefinition = "text")
    private String request;

    @Column(name = "response", columnDefinition = "text")
    private String response;

    @Column(name = "method", columnDefinition = "text")
    private String method;

    @Column(name = "request_uri", columnDefinition = "text")
    private String requestUri;

    @Column(name = "request_headers", columnDefinition = "text")
    private String requestHeaders;

    @Temporal(TemporalType.TIMESTAMP)
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, length = 29, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    protected Date createdAt = new Date();
}
