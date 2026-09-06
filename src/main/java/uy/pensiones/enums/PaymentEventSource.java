package uy.pensiones.enums;

public enum PaymentEventSource {
    CREATE,
    PROVIDER_SYNC,
    MOCK_SIMULATION,
    WEBHOOK,
    CANCEL,
    REFUND,
    AUTOMATIC_RECONCILIATION
}
