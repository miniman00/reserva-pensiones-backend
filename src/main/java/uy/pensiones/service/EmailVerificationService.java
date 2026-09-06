package uy.pensiones.service;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import uy.pensiones.mail.MailService;
import uy.pensiones.model.EmailVerificationToken;
import uy.pensiones.repo.EmailVerificationTokenRepository;
import uy.pensiones.model.User;
import uy.pensiones.repo.UserRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokens;
    private final MailService mail;
    private final UserRepository users;
    private final String frontendUrl;

    public EmailVerificationService(EmailVerificationTokenRepository tokens, MailService mail,
                                    UserRepository users,
                                    @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.tokens = tokens;
        this.mail = mail;
        this.users = users;
        this.frontendUrl = frontendUrl;
    }

    @Transactional
    public void issueIfNeeded(User user) {
        try {
            if (user.isEmailVerified()) return;
        } catch (Throwable ignored) { return; }

        Optional<EmailVerificationToken> last = tokens.findTopByUserIdAndStatusOrderByCreatedAtDesc(user.getId(), EmailVerificationToken.Status.PENDING);
        if (last.isPresent() && last.get().getExpiresAt().isAfter(OffsetDateTime.now())) return;

        String raw = UUID.randomUUID().toString();
        String hash = BCrypt.hashpw(raw, BCrypt.gensalt());
        EmailVerificationToken t = EmailVerificationToken.builder()
                .user(user)
                .tokenHash(hash)
                .expiresAt(OffsetDateTime.now().plusDays(2))
                .status(EmailVerificationToken.Status.PENDING)
                .build();
        tokens.save(t);

        String link = frontendUrl + "/verify-email?token=" + raw;
        mail.sendVerifyEmail(user.getEmail(), link);
    }

    @Transactional
    public void verify(String rawToken, Long userId) {
        EmailVerificationToken t = tokens.findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, EmailVerificationToken.Status.PENDING)
                .orElseThrow(() -> new IllegalArgumentException("No hay token de verificación vigente"));
        if (!BCrypt.checkpw(rawToken, t.getTokenHash())) throw new IllegalArgumentException("Token inválido");
        if (t.getExpiresAt().isBefore(OffsetDateTime.now())) throw new IllegalStateException("Token expirado");

        t.setStatus(EmailVerificationToken.Status.ACCEPTED);
        t.setAcceptedAt(OffsetDateTime.now());

        User u = t.getUser();
        try { u.setEmailVerified(true); } catch (Throwable ignored) {}
        users.save(u);
    }
}
