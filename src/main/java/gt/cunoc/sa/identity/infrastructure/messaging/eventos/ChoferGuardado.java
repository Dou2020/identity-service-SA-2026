package gt.cunoc.sa.identity.infrastructure.messaging.eventos;

import java.util.UUID;

/**
 * E5, lo publica fleet: identity crea o actualiza la cuenta DRIVER del chofer. Solo los campos
 * que identity usa; el resto del payload se ignora. aggregateVersion = versión del chofer.
 *
 * @param correo null si el chofer no tiene correo (entonces no tiene cuenta)
 */
public record ChoferGuardado(
        UUID choferId,
        UUID sucursalId,
        String nombreCompleto,
        String correo,
        String telefono,
        boolean activo) {
}
