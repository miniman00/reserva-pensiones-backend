package uy.pensiones.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import uy.pensiones.enums.AdmissionType;
import uy.pensiones.enums.Amenity;
import uy.pensiones.enums.BathroomType;
import uy.pensiones.enums.PensionStatus;
import uy.pensiones.enums.ResidentProfile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "pensions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Pension {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Organization org;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User createdBy;

    @Column(nullable = false, length = 140)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(length = 2, nullable = false)
    private String countryCode;

    @Column(length = 200) private String addressLine1;
    @Column(length = 100) private String city;
    @Column(length = 100) private String state;
    @Column(length = 20)  private String postalCode;

    // NUEVO: barrio/zona
    @Column(length = 120)
    private String neighborhood;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'DRAFT'")
    @Builder.Default
    private PensionStatus status = PensionStatus.DRAFT;

    /** Ultimo paso del asistente guardado para poder retomar un borrador en otro dispositivo. */
    @Column(name = "draft_step", length = 24)
    private String draftStep;

    /** Version optimista que evita sobrescribir cambios realizados desde otra pestaña o dispositivo. */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean moderationBlocked = false;

    /** Marca comercial para priorizar el anuncio en resultados públicos. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean featured = false;

    private Double lat;
    private Double lng;

    // Capacidad total por tipo
    @Min(0) private Integer capacitySimple;
    @Min(0) private Integer capacityMatrimonial;

    // Disponibilidad (debe ser <= capacidad)
    @Min(0) private Integer availableSimple;
    @Min(0) private Integer availableMatrimonial;

    private BigDecimal priceSimple;
    private BigDecimal priceMatrimonial;

    /** Última vez que el propietario o un colaborador confirmó los cupos publicados. */
    @Column
    private OffsetDateTime availabilityUpdatedAt;

    /** A quién admite la residencia. Puede ser null en registros históricos hasta que se editen. */
    @Enumerated(EnumType.STRING)
    @Column(name = "admission_type", length = 20)
    private AdmissionType admissionType;

    /** Perfil de residentes al que está orientada la pensión. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resident_profile", length = 24)
    private ResidentProfile residentProfile;

    // NUEVO: baños y tipo
    @Min(0)
    private Integer bathroomsCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BathroomType bathroomType;

    // NUEVO: parking
    private Boolean hasParking;

    // Contacto público opcional. Los flags controlan qué datos se exponen.
    @Column(length = 120)
    private String contactName;

    @Column(length = 30)
    private String contactPhone;

    @Column(length = 30)
    private String contactWhatsapp;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean showPhone = false;

    @Column(nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean showWhatsapp = false;

    // NUEVO: foto destacada para cards
    @Column(length = 255)
    private String featuredImage;

    // NUEVO: amenities (tabla aparte)
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "pension_amenities", joinColumns = @JoinColumn(name = "pension_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "amenity", length = 40)
    @Builder.Default
    private Set<Amenity> amenities = new HashSet<>();

    // NUEVO: servicios cercanos (manual, tags)
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "pension_nearby_tags", joinColumns = @JoinColumn(name = "pension_id"))
    @Column(name = "tag", length = 80)
    @Builder.Default
    private Set<String> nearbyTags = new HashSet<>();

    /** Facultades, universidades u otros centros de estudio cercanos declarados por la pensión. */
    @ElementCollection(fetch = FetchType.EAGER)
    @BatchSize(size = 100)
    @CollectionTable(name = "pension_study_centers", joinColumns = @JoinColumn(name = "pension_id"))
    @Column(name = "study_center", length = 140)
    @Builder.Default
    private Set<String> studyCenters = new HashSet<>();

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        var now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (availabilityUpdatedAt == null) availabilityUpdatedAt = now;

        capacitySimple = safe(capacitySimple);
        capacityMatrimonial = safe(capacityMatrimonial);
        availableSimple = Math.min(
                availableSimple == null ? capacitySimple : safe(availableSimple),
                capacitySimple
        );
        availableMatrimonial = Math.min(
                availableMatrimonial == null ? capacityMatrimonial : safe(availableMatrimonial),
                capacityMatrimonial
        );

        if (bathroomsCount == null) bathroomsCount = 0;
        if (hasParking == null) hasParking = false;
        if (showPhone == null) showPhone = false;
        if (showWhatsapp == null) showWhatsapp = false;
        if (moderationBlocked == null) moderationBlocked = false;
        if (featured == null) featured = false;
        if (bathroomType == null) bathroomType = BathroomType.SHARED; // default razonable
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
        capacitySimple = safe(capacitySimple);
        capacityMatrimonial = safe(capacityMatrimonial);
        availableSimple = Math.min(safe(availableSimple), capacitySimple);
        availableMatrimonial = Math.min(safe(availableMatrimonial), capacityMatrimonial);
    }

    private int safe(Integer n) { return n == null ? 0 : Math.max(n, 0); }
}
