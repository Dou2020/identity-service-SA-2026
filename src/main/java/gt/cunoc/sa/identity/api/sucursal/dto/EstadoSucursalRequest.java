package gt.cunoc.sa.identity.api.sucursal.dto;

import jakarta.validation.constraints.NotNull;

public record EstadoSucursalRequest(@NotNull Boolean activa) {
}
