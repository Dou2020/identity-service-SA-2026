package gt.cunoc.sa.identity.infrastructure.messaging.eventos;

import java.util.UUID;

/**
 * E4: customer crea el cliente de la cuenta o lo enlaza por NIT. Sale al confirmar la cuenta y
 * en cada edición del perfil; aggregateVersion = perfil.version.
 */
public record PerfilGuardado(
        UUID usuarioId,
        String nit,
        String nombreCompleto,
        String correo,
        String telefono) {
}
