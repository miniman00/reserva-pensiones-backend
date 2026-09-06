# Pensions API

Backend del portal de pensiones/residencias construido con **Spring Boot 3.3.5**, Java 17, PostgreSQL, Google OAuth2, JPA y Flyway.

## Desarrollo local

Consulta [`LOCAL_RUN.md`](LOCAL_RUN.md) para el procedimiento completo previo a producción.

Resumen:

```bash
mvn clean test
mvn clean package
mvn spring-boot:run
```

Configuración por defecto del perfil `dev`:

```text
PostgreSQL: jdbc:postgresql://localhost:5432/pensions
Usuario:    pensions
Password:   pensions
Frontend:   http://localhost:5173
```

Las credenciales de Google deben definirse mediante `GOOGLE_CLIENT_ID` y `GOOGLE_CLIENT_SECRET`.

## Base de datos

Desde el bloque 23 el esquema está administrado por **Flyway**:

```text
src/main/resources/db/migration/
```

`V001__current_schema.sql` representa el esquema acumulado hasta el bloque 22 y está preparado para una base nueva o una base existente creada previamente con Hibernate/scripts manuales.

Los scripts antiguos de:

```text
src/main/resources/sql/
```

se conservan como historial y **no deben ejecutarse manualmente en instalaciones nuevas**.

Hibernate utiliza `ddl-auto=validate`; cualquier evolución posterior del esquema debe agregarse como una nueva migración Flyway (`V002__...`, `V003__...`, etc.).

## Endpoints operativos

```text
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /sitemap.xml
GET /robots.txt
```

Smoke tests:

```bash
bash scripts/smoke-api.sh
```

PowerShell:

```powershell
.\scripts\smoke-api.ps1
```

## Producción

Usa `.env.prod.example` como inventario de variables requeridas y ejecuta siempre con:

```text
SPRING_PROFILES_ACTIVE=prod
```

No uses secretos dentro de `application*.yml` ni `ddl-auto=update` en producción.
