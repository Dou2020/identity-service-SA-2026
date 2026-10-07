package gt.cunoc.sa.identity.api.usuario.dto;

import jakarta.validation.constraints.NotNull;

public record EstadoUsuarioRequest(@NotNull Boolean activo) {
}
