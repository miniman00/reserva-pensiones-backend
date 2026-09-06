# Media storage, backup y escalado

## Estado actual

El backend usa `MediaStorage` como contrato y `LocalMediaStorage` como implementación por defecto.
La lógica de negocio trabaja con claves como `pensions/123/archivo.jpg` y ya no necesita conocer
rutas físicas. Esto permite agregar más adelante un adaptador S3/R2/MinIO sin modificar uploads,
variantes, mantenimiento ni DTOs.

Configuración actual:

```text
APP_MEDIA_STORAGE_PROVIDER=local
MEDIA_ROOT=/var/lib/pensions/uploads
```

`local` requiere un volumen persistente. Si existen varias instancias del backend, todas deben ver
el mismo `MEDIA_ROOT`; de lo contrario no es seguro escalar horizontalmente.

## Migración futura a object storage

La implementación futura debe implementar `MediaStorage` y, como mínimo:

- escritura atómica o equivalente;
- lectura como `Resource` o endpoint proxy;
- `exists`, `lastModified`, `delete` y `list(prefix)`;
- una `deliveryUrl` que no haga público contenido privado accidentalmente;
- health/readiness del proveedor;
- consistencia de las claves actuales (`pensions/{id}/...`).

No se debe cambiar el nombre de las claves al migrar. De esa forma la BD no necesita reescribir
`filename` ni las referencias de portada.

## Backup mínimo consistente

Un backup recuperable del portal contiene **dos conjuntos**:

1. PostgreSQL (`pg_dump` en formato custom recomendado).
2. Todos los objetos bajo `MEDIA_ROOT` o el bucket de object storage.

Guardar ambos bajo el mismo identificador de backup, por ejemplo:

```text
2026-09-03T180000Z/
  database.dump
  media.tar.zst
  manifest.sha256
```

Para filesystem local es preferible realizar snapshot del volumen cuando el proveedor lo soporte.
Si no hay snapshots, hacer el backup en una ventana de bajo tráfico y conservar el período de gracia
de reconciliación para no eliminar objetos recién creados durante la copia.

## Restore

1. Detener escrituras o desplegar una instancia de recuperación aislada.
2. Restaurar PostgreSQL.
3. Restaurar media usando exactamente las mismas claves/rutas relativas.
4. Arrancar backend y esperar Flyway.
5. Verificar `/actuator/health/readiness`.
6. Ejecutar reconciliación de media primero con `cleanup=false`.
7. Probar imágenes antiguas, un video y una publicación en borrador.
8. Verificar outbox de correo y reconciliación de pagos.
9. Solo después habilitar tráfico público.

Nunca ejecutar limpieza de huérfanos inmediatamente después de un restore incompleto.

## Prueba periódica de recuperación

Un backup que nunca se restauró no debe considerarse validado. Al menos mensualmente:

- restaurar en un entorno temporal;
- contar pensiones y filas de `pension_media`;
- comprobar que los objetos referenciados existen;
- abrir varias imágenes/videos históricos;
- ejecutar smoke API;
- registrar fecha, backup utilizado y resultado.

## Cuándo migrar a R2/S3/MinIO

Conviene hacerlo antes de escalar a varias instancias si no se dispone de un volumen compartido
fiable, o cuando backups/CDN/egress del filesystem empiecen a ser una carga operativa. La interfaz
actual permite hacerlo agregando un adaptador, no reescribiendo `PensionMediaService`.
