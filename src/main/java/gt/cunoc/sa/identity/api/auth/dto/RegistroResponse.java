package gt.cunoc.sa.identity.api.auth.dto;

import java.util.UUID;

public record RegistroResponse(UUID usuarioId, String mensaje) {
}
