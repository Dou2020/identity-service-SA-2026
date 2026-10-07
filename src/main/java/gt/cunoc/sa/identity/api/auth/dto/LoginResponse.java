package gt.cunoc.sa.identity.api.auth.dto;

import java.time.Instant;

import gt.cunoc.sa.identity.api.usuario.dto.UsuarioResponse;

/** El frontend guarda el token y arma el menú con el rol del usuario. */
public record LoginResponse(String token, Instant expiraEn, UsuarioResponse usuario) {
}
