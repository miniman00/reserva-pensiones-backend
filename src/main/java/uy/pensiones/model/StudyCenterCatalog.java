package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "study_center_catalog",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_study_center_catalog_normalized_name",
                columnNames = "normalized_name"
        )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudyCenterCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 140)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 140)
    private String normalizedName;

    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(length = 100)
    private String city;

    @Column(length = 220)
    private String address;

    private Double lat;
    private Double lng;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean verified = false;

    @Column(nullable = false, columnDefinition = "boolean default true")
    @Builder.Default
    private Boolean active = true;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        var now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (verified == null) verified = false;
        if (active == null) active = true;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
        if (verified == null) verified = false;
        if (active == null) active = true;
    }
}
