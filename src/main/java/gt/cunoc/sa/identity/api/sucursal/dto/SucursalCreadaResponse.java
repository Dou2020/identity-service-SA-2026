package gt.cunoc.sa.identity.api.sucursal.dto;

import gt.cunoc.sa.identity.api.usuario.dto.UsuarioResponse;

public record SucursalCreadaResponse(SucursalResponse sucursal, UsuarioResponse administrador) {
}
