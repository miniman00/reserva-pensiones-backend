package uy.pensiones.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "mail_outbox")
public class MailOutboxMessage {
    public enum Status { PENDING, DEAD }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 320) private String recipient;
    @Column(length = 320) private String replyTo;
    @Column(nullable = false, length = 300) private String subject;
    @Column(nullable = false, columnDefinition = "text") private String htmlBody;
    @Column(nullable = false, length = 80) private String category;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status = Status.PENDING;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false) private OffsetDateTime nextAttemptAt;
    @Column(length = 80) private String leaseOwner;
    private OffsetDateTime leaseUntil;
    @Column(length = 500) private String lastError;
    @Column(nullable = false) private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (nextAttemptAt == null) nextAttemptAt = now;
        if (status == null) status = Status.PENDING;
    }

    public Long getId() { return id; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getHtmlBody() { return htmlBody; }
    public void setHtmlBody(String htmlBody) { this.htmlBody = htmlBody; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public OffsetDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(OffsetDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
