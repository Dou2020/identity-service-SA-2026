package gt.cunoc.sa.identity.api.interno.dto;

import java.util.UUID;

/** S7: a quién le escribe notification el recordatorio de licencia. */
public record AdministradorResponse(UUID usuarioId, String nombreCompleto, String correo) {
}
