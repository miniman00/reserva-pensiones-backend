# Operación de producción - Pensiones API

## Health y apagado

- Liveness: `/actuator/health/liveness`
- Readiness: `/actuator/health/readiness`
- Readiness incluye base de datos y almacenamiento de media.
- Producción usa `server.shutdown=graceful` y `spring.lifecycle.timeout-per-shutdown-phase`.

El almacenamiento de media debe ser un volumen persistente y compartido si hay más de una instancia. No usar el filesystem efímero del contenedor para `MEDIA_ROOT`.

## Almacenamiento de fotos y videos

`MEDIA_ROOT` debe apuntar a un volumen respaldado. La sonda de readiness pasa a `DOWN` si el directorio no es escribible o si queda menos del mínimo configurable de espacio utilizable.

Variables opcionales:

```text
APP_MEDIA_MAINTENANCE_MIN_USABLE_BYTES=134217728
APP_MEDIA_MAINTENANCE_CLEANUP_ENABLED=false
APP_MEDIA_MAINTENANCE_ORPHAN_GRACE=PT24H
```

La reconciliación nocturna siempre informa archivos referenciados ausentes y archivos huérfanos. La eliminación automática viene deshabilitada. Activarla solo después de observar los logs al menos un ciclo completo y confirmar que `MEDIA_ROOT` es el volumen correcto.

El período de gracia evita eliminar un archivo creado recientemente mientras una operación todavía está en curso. Los uploads nuevos también registran limpieza en rollback de transacción; las eliminaciones físicas se ejecutan únicamente después del commit de BD.

## Correo durable

Los correos funcionales se guardan primero en `mail_outbox` dentro de la misma transacción que los solicita. Un worker los entrega posteriormente por SMTP/Brevo.

Defaults:

```text
APP_MAIL_OUTBOX_BATCH_SIZE=20
APP_MAIL_OUTBOX_MAX_ATTEMPTS=8
APP_MAIL_OUTBOX_LEASE=PT15M
APP_MAIL_OUTBOX_POLL_DELAY_MS=15000
```

Backoff: 1 min, 5 min, 15 min, 1 h, 3 h y luego 6 h. Los mensajes enviados se eliminan para no retener su contenido. Los que agotan reintentos quedan `DEAD` durante 7 días para diagnóstico y luego se purgan; al pasar a `DEAD` se elimina el cuerpo HTML y el reply-to para no conservar tokens o mensajes privados innecesariamente.

La entrega es **at-least-once**: un crash extremadamente preciso después de que el proveedor aceptó el correo pero antes de confirmar el borrado del outbox puede producir un duplicado, pero evita perder silenciosamente correos críticos.

Consulta operativa útil:

```sql
select status, count(*)
from mail_outbox
group by status;

select id, category, attempts, next_attempt_at, last_error, created_at
from mail_outbox
where status = 'DEAD'
order by created_at desc;
```

Las alertas operativas de pagos siguen siendo síncronas porque el Backoffice necesita mostrar el resultado inmediato de la prueba de transporte.

## Backups y restauración

La restauración completa requiere dos recursos coherentes:

1. PostgreSQL.
2. `MEDIA_ROOT`.

Como mínimo mantener backups automáticos de ambos y ejecutar periódicamente una restauración de prueba. Para PostgreSQL autogestionado, un ejemplo de backup lógico es:

```bash
pg_dump --format=custom --no-owner --file=pensions.dump "$DB_URL"
```

Si la base está en un proveedor administrado, preferir sus snapshots/PITR y documentar el procedimiento de restore. Para media, usar snapshots/versionado del volumen u object storage equivalente.

Después de restaurar, comprobar en este orden:

1. Flyway finaliza sin errores.
2. `/actuator/health/readiness` devuelve `UP`.
3. Buscar una pensión y abrir varias fotos/videos históricos.
4. Revisar logs de reconciliación de media antes de habilitar cualquier limpieza automática.
5. Verificar `mail_outbox` y conciliación de pagos.

## Deploy con varias instancias

- Todas las instancias deben usar la misma BD.
- Todas deben ver el mismo `MEDIA_ROOT`, o migrar media a almacenamiento de objetos antes de escalar horizontalmente.
- La conciliación de pagos ya usa lease de BD.
- El outbox de correo usa `FOR UPDATE SKIP LOCKED` + lease, por lo que admite múltiples instancias.
- Los webhooks de Mercado Pago son persistidos/idempotentes antes de aplicar efectos.


## Almacenamiento y recuperación de media

La abstracción de almacenamiento, estrategia de backup/restore y preparación para object storage se documentan en `docs/MEDIA_STORAGE_AND_BACKUP.md`.
