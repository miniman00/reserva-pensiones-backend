package uy.pensiones.enums;

public enum PaymentAlertSeverity {
    MEDIUM(3),
    HIGH(2),
    CRITICAL(1);

    private final int rank;

    PaymentAlertSeverity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
