# CI/CD backend (GitHub Actions + Render + Neon)

## Flujo base

El repositorio ejecuta `.github/workflows/ci.yml` en Pull Requests y en `main`:

1. PostgreSQL 17 efímero en GitHub Actions.
2. Java 17 (Temurin).
3. `mvn verify`.
4. Arranque real del JAR con perfil `ci`.
5. Flyway aplica todas las migraciones sobre una base vacía.
6. Hibernate `ddl-auto=validate` verifica el esquema resultante.
7. Readiness + `scripts/smoke-api.sh` validan el servicio.
8. El JAR se publica como artifact temporal de CI.

El perfil `application-ci.yml` no habilita correo, monetización ni pagos externos.

## Render

Mantener Render como plataforma de producción.

En el servicio backend:

- Branch: `main`.
- Auto-Deploy: `After CI Checks Pass`.
- Health Check Path recomendado: `/actuator/health/readiness`.
- Language/Runtime: `Docker`. Render recomienda Docker para aplicaciones JVM.
- Dockerfile Path: `./Dockerfile`.
- El contenedor ejecuta Java 17, igual que CI y `pom.xml`.
- No configurar manualmente `PORT`: Render lo inyecta y `application.yml` ya usa `${PORT:8080}`.
- Mantener los secrets de producción únicamente en Render; no pasarlos como build args.
- Si `APP_MEDIA_STORAGE_PROVIDER=local`, usar un servicio pago con Persistent Disk y montar `/var/lib/pensions`; `MEDIA_ROOT=/var/lib/pensions/uploads`.

Con `After CI Checks Pass`, un commit de `main` que falle CI no debe desplegarse automáticamente.

## Branch protection de GitHub

Proteger `main` y requerir Pull Request. Check requerido:

- `Backend CI / Test, migrate and startup smoke`.

Bloquear pushes directos a `main` evita saltarse el gate antes del merge.

## Validación opcional con Neon

`.github/workflows/neon-migration-preview.yml` viene desactivado de forma segura.

Para activarlo:

1. Instalar/configurar la integración de Neon con GitHub o crear un API key.
2. Crear repository secret:
   - `NEON_API_KEY`.
3. Crear repository variables:
   - `NEON_CI_ENABLED=true`;
   - `NEON_PROJECT_ID=<project-id>`;
   - `NEON_DATABASE=neondb` (o el nombre real);
   - `NEON_ROLE=neondb_owner` (o el rol real).

En cada PR el workflow crea una branch temporal de Neon desde la branch primaria, arranca el backend contra ella y deja que Flyway aplique únicamente las migraciones pendientes. Al terminar elimina la branch; además se configura expiración de 24 horas como red de seguridad.

No activar `NEON_CI_ENABLED` hasta haber comprobado que `NEON_DATABASE` y `NEON_ROLE` coinciden con producción.

## Migraciones

Reglas operativas:

- nunca editar una migración Flyway que ya llegó a producción;
- agregar siempre una versión nueva `Vxxx`;
- mantener `clean-disabled=true`;
- cualquier cambio de esquema debe pasar primero el PostgreSQL efímero de CI;
- para cambios delicados, activar Neon preview antes del merge.

## Rollback

El rollback de código en Render no revierte automáticamente una migración de BD.

Las migraciones de producción deben diseñarse con estrategia expand/contract cuando sean incompatibles:

1. agregar columnas/tablas compatibles;
2. desplegar código que soporte ambos estados;
3. migrar/backfill;
4. retirar el esquema antiguo en una versión posterior.

Ante incidente, priorizar rollback de código solo cuando el esquema siga siendo compatible.
