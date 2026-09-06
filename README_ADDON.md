# Add-on: Organizaciones, Membresías, Invitaciones y Verificación de Email

- Entidades: `Organization`, `Membership`, `OrgInvite`, `EmailVerificationToken`
- Repositorios JPA
- Servicios: `OrgService`, `InviteService`, `EmailVerificationService`, `MailService`, `Authz`
- Controladores: `/api/orgs`, `/api/invites/accept`, `/api/auth/verify-email`
- `@EnableMethodSecurity` en `MethodSecurityConfig`

## Agregar dependencia en `pom.xml`
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

## Configuración de correo

El envío usa el mismo patrón que Interview Trainer / Seshat:

```text
APP_MAIL_ENABLED=true
APP_MAIL_PROVIDER=smtp|brevo
APP_MAIL_FROM=no-reply@codevaru.com
APP_BREVO_API_KEY=
APP_BREVO_ENDPOINT=https://api.brevo.com/v3/smtp/email
```

Para SMTP (por ejemplo Gmail en local) se usan las properties estándar de Spring Boot:

```text
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=tu-cuenta@gmail.com
SPRING_MAIL_PASSWORD=tu-password-de-aplicacion
```

En producción, `APP_MAIL_PROVIDER=brevo` envía por la API HTTP de Brevo y no requiere credenciales SMTP.
