package uy.pensiones.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(
        name = "plan_version_period_prices",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_plan_version_period_prices_version_months",
                columnNames = {"plan_version_id", "period_months"}
        )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PlanVersionPeriodPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_version_id", nullable = false)
    private PlanVersion planVersion;

    @Column(name = "period_months", nullable = false)
    private int periodMonths;

    @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;
}
