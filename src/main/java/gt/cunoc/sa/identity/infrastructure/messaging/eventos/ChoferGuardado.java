package gt.cunoc.sa.identity.infrastructure.messaging.eventos;

import java.util.UUID;

/**
 * E5, lo publica fleet: identity crea o actualiza la cuenta DRIVER del chofer. Solo los campos
 * que identity usa; el resto del payload se ignora. aggregateVersion = versión del chofer.
 *
 * @param choferId id del chofer en fleet, como texto ("3"): identity lo guarda y lo pone en el JWT
 *                 sin interpretarlo. sucursalId sí es UUID, porque es un id de identity (E1)
 * @param correo   null si el chofer no tiene correo (entonces no tiene cuenta)
 */
public record ChoferGuardado(
        String choferId,
        UUID sucursalId,
        String nombreCompleto,
        String correo,
        String telefono,
        boolean activo) {
}
