package uy.pensiones.enums;

public enum PaymentStatus {
    CREATED,
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    REFUNDED,
    EXPIRED;

    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED || this == REFUNDED || this == EXPIRED;
    }
}
