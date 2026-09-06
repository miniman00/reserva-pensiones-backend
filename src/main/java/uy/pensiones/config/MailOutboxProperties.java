package uy.pensiones.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.mail.outbox")
public class MailOutboxProperties {
    private int batchSize = 20;
    private int maxAttempts = 8;
    private Duration lease = Duration.ofMinutes(15);
    private Duration deadRetention = Duration.ofDays(7);

    public int getBatchSize() { return Math.max(1, Math.min(100, batchSize)); }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxAttempts() { return Math.max(1, Math.min(20, maxAttempts)); }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getLease() { return lease == null || lease.isNegative() ? Duration.ofMinutes(15) : lease; }
    public void setLease(Duration lease) { this.lease = lease; }
    public Duration getDeadRetention() { return deadRetention == null || deadRetention.isNegative() ? Duration.ofDays(7) : deadRetention; }
    public void setDeadRetention(Duration deadRetention) { this.deadRetention = deadRetention; }
}
