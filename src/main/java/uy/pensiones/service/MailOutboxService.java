package uy.pensiones.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uy.pensiones.config.MailOutboxProperties;
import uy.pensiones.model.MailOutboxMessage;
import uy.pensiones.repo.MailOutboxRepository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class MailOutboxService {
    private static final Logger log = LoggerFactory.getLogger(MailOutboxService.class);

    private final MailOutboxRepository outbox;
    private final MailOutboxProperties properties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final RestClient restClient;
    private final boolean mailEnabled;
    private final String mailProvider;
    private final String from;
    private final String brevoApiKey;
    private final String brevoEndpoint;
    private final TransactionTemplate transactions;

    public MailOutboxService(MailOutboxRepository outbox,
                             MailOutboxProperties properties,
                             ObjectProvider<JavaMailSender> mailSenderProvider,
                             RestClient.Builder restClientBuilder,
                             @Value("${app.mail.enabled:false}") boolean mailEnabled,
                             @Value("${app.mail.provider:smtp}") String mailProvider,
                             @Value("${app.mail.from:no-reply@codevaru.com}") String from,
                             @Value("${app.mail.brevo.api-key:}") String brevoApiKey,
                             @Value("${app.mail.brevo.endpoint:https://api.brevo.com/v3/smtp/email}") String brevoEndpoint,
                             PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.properties = properties;
        this.mailSenderProvider = mailSenderProvider;
        this.restClient = restClientBuilder.build();
        this.mailEnabled = mailEnabled;
        this.mailProvider = mailProvider;
        this.from = from;
        this.brevoApiKey = brevoApiKey;
        this.brevoEndpoint = brevoEndpoint;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public void enqueue(String to, String subject, String html, String replyTo, String category) {
        if (!mailEnabled) {
            log.info("Email omitido porque app.mail.enabled=false. category={}, to={}, subject={}", category, to, subject);
            return;
        }
        MailOutboxMessage message = new MailOutboxMessage();
        message.setRecipient(to == null ? "" : to.trim());
        message.setReplyTo(replyTo == null || replyTo.isBlank() ? null : replyTo.trim());
        message.setSubject(subject == null ? "" : subject.trim());
        message.setHtmlBody(html == null ? "" : html);
        message.setCategory(category == null || category.isBlank() ? "GENERAL" : category.trim());
        outbox.save(message);
    }

    @Scheduled(fixedDelayString = "${app.mail.outbox.poll-delay-ms:15000}", initialDelayString = "${app.mail.outbox.initial-delay-ms:10000}")
    public void deliverPending() {
        if (!mailEnabled) return;
        String owner = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Integer claimedValue = transactions.execute(status -> outbox.claimBatch(
                owner, now, now.plus(properties.getLease()), properties.getBatchSize()));
        int claimed = claimedValue == null ? 0 : claimedValue;
        if (claimed <= 0) return;
        List<MailOutboxMessage> messages = outbox.findByLeaseOwnerAndStatusOrderByIdAsc(owner, MailOutboxMessage.Status.PENDING);
        for (MailOutboxMessage message : messages) {
            Delivery delivery = deliver(message);
            if (delivery.sent()) {
                transactions.executeWithoutResult(status -> markSent(message.getId(), owner));
            } else {
                transactions.executeWithoutResult(status -> markFailed(message.getId(), owner, delivery.error()));
            }
        }
    }

    @Scheduled(cron = "${app.mail.outbox.cleanup-cron:0 20 4 * * *}", zone = "${app.mail.outbox.cleanup-zone:UTC}")
    @Transactional
    public void cleanupDead() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(properties.getDeadRetention());
        int deleted = outbox.deleteByStatusAndCreatedAtBefore(MailOutboxMessage.Status.DEAD, cutoff);
        if (deleted > 0) log.info("Mail outbox: se eliminaron {} mensajes DEAD fuera de retención", deleted);
    }

    void markSent(Long id, String owner) {
        outbox.findById(id).filter(m -> owner.equals(m.getLeaseOwner())).ifPresent(message -> {
            String category = message.getCategory();
            outbox.delete(message);
            log.info("Email enviado desde outbox. id={}, category={}", id, category);
        });
    }

    void markFailed(Long id, String owner, String error) {
        outbox.findById(id).filter(m -> owner.equals(m.getLeaseOwner())).ifPresent(message -> {
            int attempts = message.getAttempts() + 1;
            message.setAttempts(attempts);
            message.setLeaseOwner(null);
            message.setLeaseUntil(null);
            message.setLastError(safeError(error));
            if (attempts >= properties.getMaxAttempts()) {
                message.setStatus(MailOutboxMessage.Status.DEAD);
                // El contenido puede incluir tokens o datos de una consulta. Una vez agotados
                // los reintentos ya no es necesario conservar el cuerpo para diagnóstico.
                message.setHtmlBody("");
                message.setReplyTo(null);
                log.error("Email agotó reintentos y quedó DEAD. id={}, category={}, attempts={}, error={}",
                        id, message.getCategory(), attempts, message.getLastError());
            } else {
                Duration backoff = retryDelay(attempts);
                message.setNextAttemptAt(OffsetDateTime.now(ZoneOffset.UTC).plus(backoff));
                log.warn("Email pendiente de reintento. id={}, category={}, attempt={}, retryIn={}, error={}",
                        id, message.getCategory(), attempts, backoff, message.getLastError());
            }
            outbox.save(message);
        });
    }

    private Delivery deliver(MailOutboxMessage message) {
        String provider = mailProvider == null || mailProvider.isBlank() ? "smtp" : mailProvider.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (provider) {
                case "smtp" -> deliverSmtp(message);
                case "brevo" -> deliverBrevo(message);
                default -> new Delivery(false, "Proveedor de correo no soportado: " + provider);
            };
        } catch (RuntimeException ex) {
            return new Delivery(false, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private Delivery deliverSmtp(MailOutboxMessage message) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) return new Delivery(false, "JavaMailSender no está configurado");
        try {
            MailAddress senderAddress = parseMailAddress(from, "Pensiones");
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, "UTF-8");
            if (senderAddress.name() == null || senderAddress.name().isBlank()) helper.setFrom(senderAddress.email());
            else helper.setFrom(senderAddress.email(), senderAddress.name());
            helper.setTo(message.getRecipient());
            if (message.getReplyTo() != null && !message.getReplyTo().isBlank()) helper.setReplyTo(message.getReplyTo());
            helper.setSubject(message.getSubject());
            helper.setText(message.getHtmlBody(), true);
            sender.send(mime);
            return new Delivery(true, null);
        } catch (Exception ex) {
            return new Delivery(false, ex.getMessage());
        }
    }

    private Delivery deliverBrevo(MailOutboxMessage message) {
        if (brevoApiKey == null || brevoApiKey.isBlank()) return new Delivery(false, "APP_BREVO_API_KEY está vacío");
        try {
            MailAddress parsedFrom = parseMailAddress(from, "Pensiones");
            BrevoEmailRequest request = new BrevoEmailRequest(
                    new BrevoMailAddress(parsedFrom.email(), parsedFrom.name()),
                    List.of(new BrevoMailAddress(message.getRecipient(), null)),
                    message.getReplyTo() == null ? null : new BrevoMailAddress(message.getReplyTo(), null),
                    message.getSubject(), message.getHtmlBody(), List.of("pensiones", message.getCategory().toLowerCase(Locale.ROOT))
            );
            restClient.post().uri(brevoEndpoint).contentType(MediaType.APPLICATION_JSON)
                    .header("api-key", brevoApiKey.trim()).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .body(request).retrieve().toBodilessEntity();
            return new Delivery(true, null);
        } catch (RestClientResponseException ex) {
            return new Delivery(false, "Brevo respondió " + ex.getStatusCode());
        } catch (Exception ex) {
            return new Delivery(false, ex.getMessage());
        }
    }

    private Duration retryDelay(int attempts) {
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            case 4 -> Duration.ofHours(1);
            case 5 -> Duration.ofHours(3);
            default -> Duration.ofHours(6);
        };
    }

    private String safeError(String value) {
        if (value == null || value.isBlank()) return "error desconocido";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    private MailAddress parseMailAddress(String rawValue, String defaultName) {
        String fallbackEmail = "no-reply@codevaru.com";
        String fallbackName = defaultName == null || defaultName.isBlank() ? "Pensiones" : defaultName.trim();
        if (rawValue == null || rawValue.isBlank()) return new MailAddress(fallbackEmail, fallbackName);
        String value = rawValue.trim();
        int open = value.indexOf('<');
        int close = value.indexOf('>');
        if (open >= 0 && close > open) {
            String name = value.substring(0, open).trim();
            String email = value.substring(open + 1, close).trim();
            return new MailAddress(email.isBlank() ? fallbackEmail : email,
                    name.isBlank() ? fallbackName : stripQuotes(name));
        }
        return new MailAddress(value, fallbackName);
    }

    private String stripQuotes(String value) {
        String result = value == null ? "" : value.trim();
        if (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private record Delivery(boolean sent, String error) {}
    private record MailAddress(String email, String name) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BrevoEmailRequest(BrevoMailAddress sender, List<BrevoMailAddress> to,
                                     BrevoMailAddress replyTo, String subject, String htmlContent,
                                     List<String> tags) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BrevoMailAddress(String email, String name) {}
}
