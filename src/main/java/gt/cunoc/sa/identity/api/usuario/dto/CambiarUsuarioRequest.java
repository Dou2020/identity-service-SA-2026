package gt.cunoc.sa.identity.api.usuario.dto;

import java.util.UUID;

import gt.cunoc.sa.identity.domain.Rol;
import jakarta.validation.constraints.NotNull;

/** @param sucursalId obligatoria si el rol nuevo es BRANCH_ADMIN o CASHIER */
public record CambiarUsuarioRequest(@NotNull Rol rol, UUID sucursalId) {
}
