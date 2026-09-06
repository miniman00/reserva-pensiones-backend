# Ejecución local antes de producción

A partir del bloque 23 el esquema de PostgreSQL es administrado por **Flyway**. Hibernate usa `ddl-auto=validate` también en desarrollo: si falta una tabla/columna, el backend debe fallar en el arranque en lugar de modificar la base silenciosamente.

## 1. Requisitos

- Java 17 o superior.
- Maven 3.9 o superior.
- PostgreSQL accesible localmente.
- Node/NPM para el frontend.
- Credenciales OAuth de Google con redirect local:
  `http://localhost:8080/login/oauth2/code/google`.

## 2. Base local

La configuración por defecto usa:

```text
jdbc:postgresql://localhost:5432/pensions
usuario: pensions
password: pensions
```

Puedes sobrescribirla con `DB_URL`, `DB_USER` y `DB_PASSWORD`.

No ejecutes manualmente los scripts de `src/main/resources/sql` para una instalación nueva. Quedan únicamente como historial de los bloques anteriores. Flyway usa exclusivamente `src/main/resources/db/migration`.

### Base existente de los bloques anteriores

No necesitas borrarla. En el primer arranque Flyway detecta un esquema no vacío sin historial, crea un baseline en versión `0` y ejecuta `V001__current_schema.sql`, que es idempotente y completa el esquema actual.

Antes del primer arranque local es recomendable hacer un backup:

```bash
pg_dump -Fc pensions > pensions_pre_flyway.backup
```

## 3. Variables backend

Usa `.env.local.example` como referencia. En PowerShell, por ejemplo:

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
$env:DB_URL="jdbc:postgresql://localhost:5432/pensions"
$env:DB_USER="pensions"
$env:DB_PASSWORD="pensions"
$env:GOOGLE_CLIENT_ID="..."
$env:GOOGLE_CLIENT_SECRET="..."
$env:APP_FRONTEND_URL="http://localhost:5173"
```

### Correo local (Gmail SMTP)

Pensiones usa el mismo esquema de properties que Interview Trainer / Seshat.
La lógica de negocio no cambia entre ambientes: solamente cambia `APP_MAIL_PROVIDER`.

Para enviar correos reales desde local:

```powershell
$env:APP_MAIL_ENABLED="true"
$env:APP_MAIL_PROVIDER="smtp"
$env:APP_MAIL_FROM="tu-cuenta@gmail.com"
$env:SPRING_MAIL_HOST="smtp.gmail.com"
$env:SPRING_MAIL_PORT="587"
$env:SPRING_MAIL_USERNAME="tu-cuenta@gmail.com"
$env:SPRING_MAIL_PASSWORD="tu-password-de-aplicacion"
```

`SPRING_MAIL_PASSWORD` debe ser una contraseña de aplicación de Google.
Si no quieres enviar correos durante una prueba, usa `APP_MAIL_ENABLED=false`.

En producción con Brevo se usan las mismas properties de aplicación:

```text
APP_MAIL_ENABLED=true
APP_MAIL_PROVIDER=brevo
APP_MAIL_FROM=no-reply@codevaru.com
APP_BREVO_API_KEY=...
APP_BREVO_ENDPOINT=https://api.brevo.com/v3/smtp/email
```

Con `APP_MAIL_PROVIDER=brevo` no es necesario configurar `SPRING_MAIL_*`.

## 4. Verificación backend

```bash
mvn clean test
mvn clean package
mvn spring-boot:run
```

En otra terminal:

```bash
bash scripts/smoke-api.sh
```

En Windows/PowerShell:

```powershell
.\scripts\smoke-api.ps1
```

Debes verificar también:

- `http://localhost:8080/actuator/health/readiness`
- `http://localhost:8080/sitemap.xml`
- `http://localhost:8080/robots.txt`

Para confirmar Flyway en PostgreSQL:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

Debe existir baseline `0` si la BD era previa, seguido por `1 / current schema`; en una BD completamente nueva puede aparecer directamente la migración `1`.

## 5. Frontend

```bash
npm ci
npm test
npm run build
npm run smoke
npm run dev
```

Configuración local recomendada:

```text
VITE_API_BASE_URL=/api
VITE_PROXY_TARGET=http://localhost:8080
```

## 6. Recorridos manuales mínimos

1. Visitante: buscar → filtrar → mapa → ficha → consulta → reporte.
2. Usuario: Google login → consentimiento legal → favoritos → consultas → notificaciones.
3. Propietario: crear → fotos → preview → publicar → disponibilidad → consultas.
4. Colaborador: aceptar invitación → actualizar disponibilidad → comprobar restricciones.
5. Admin: moderación → pausar/desbloquear → centros de estudio.

No subir a producción hasta que tests, build, smoke y estos recorridos estén correctos en local/staging.
