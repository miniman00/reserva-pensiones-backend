package uy.pensiones.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
  private String frontendUrl = "http://localhost:5173";
  private Cors cors = new Cors();
  private Legal legal = new Legal();
  private FeatureSwitch monetization = new FeatureSwitch();
  private Payments payments = new Payments();

  public static class FeatureSwitch {
    private boolean enabled = false;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
  }

  public static class Payments {
    /** Fusible de infraestructura. La operación normal de pagos se administra en BD/Backoffice. */
    private boolean allowed = false;
    /** Clave Base64 de 32 bytes usada exclusivamente para cifrar credenciales guardadas en BD. */
    private String secretsMasterKey;

    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public String getSecretsMasterKey() { return secretsMasterKey; }
    public void setSecretsMasterKey(String secretsMasterKey) { this.secretsMasterKey = secretsMasterKey; }
  }

  public static class Legal {
    private String operatorName = "CodeVaru";
    private String contactEmail = "soporte@codevaru.com";

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
  }

  public static class Cors {
    private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:5175");
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
  }

  public String getFrontendUrl() { return frontendUrl; }
  public void setFrontendUrl(String frontendUrl) { this.frontendUrl = frontendUrl; }
  public Cors getCors() { return cors; }
  public void setCors(Cors cors) { this.cors = cors; }
  public Legal getLegal() { return legal; }
  public void setLegal(Legal legal) { this.legal = legal; }
  public FeatureSwitch getMonetization() { return monetization; }
  public void setMonetization(FeatureSwitch monetization) { this.monetization = monetization; }
  public Payments getPayments() { return payments; }
  public void setPayments(Payments payments) { this.payments = payments; }
}
