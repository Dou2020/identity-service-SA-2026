package gt.cunoc.sa.identity.infrastructure.messaging.eventos;

import java.time.Instant;
import java.util.UUID;

/** E17: notification envía el enlace de recuperación. Lleva el token, como E3. */
public record RecuperacionSolicitada(
        UUID usuarioId,
        String correo,
        String nombreCompleto,
        String token,
        Instant expiraEn) {
}
