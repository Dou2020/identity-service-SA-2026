package gt.cunoc.sa.identity.infrastructure.messaging.eventos;

import java.time.Instant;
import java.util.UUID;

import gt.cunoc.sa.identity.domain.Rol;

/**
 * E3: notification envía el correo de bienvenida con el enlace para definir la contraseña.
 * Lleva el token: el relay vacía el payload de la outbox al publicarlo.
 */
public record UsuarioCreado(
        UUID usuarioId,
        String correo,
        String nombreCompleto,
        Rol rol,
        UUID sucursalId,
        String token,
        Instant expiraEn) {
}
