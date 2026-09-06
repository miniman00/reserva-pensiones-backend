package uy.pensiones.service;

/** Monetization was enabled without a deterministic entitlement configuration. */
public class MonetizationConfigurationException extends RuntimeException {
    private final String configurationCode;

    public MonetizationConfigurationException(String configurationCode, String message) {
        super(message);
        this.configurationCode = configurationCode;
    }

    public String getConfigurationCode() { return configurationCode; }
}
