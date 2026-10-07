package gt.cunoc.sa.identity.api.usuario.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import gt.cunoc.sa.identity.domain.PerfilEntity;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.UsuarioEntity;

/**
 * @param confirmado false mientras no define su contraseña con el enlace
 * @param perfil     solo en el detalle; las listas no lo traen
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsuarioResponse(
        UUID id,
        String correo,
        String nombreCompleto,
        Rol rol,
        UUID sucursalId,
        UUID choferId,
        boolean activo,
        boolean confirmado,
        Instant creadoEn,
        PerfilResumen perfil) {

    public record PerfilResumen(String nit, String dpi, String telefono, String direccion, boolean completo) {
    }

    public static UsuarioResponse de(UsuarioEntity u) {
        return new UsuarioResponse(u.getId(), u.getCorreo(), u.getNombreCompleto(), u.getRol(), u.getSucursalId(),
                u.getChoferId(), u.isActivo(), u.isConfirmado(), u.getCreadoEn(), null);
    }

    public static UsuarioResponse de(UsuarioEntity u, PerfilEntity p) {
        return new UsuarioResponse(u.getId(), u.getCorreo(), u.getNombreCompleto(), u.getRol(), u.getSucursalId(),
                u.getChoferId(), u.isActivo(), u.isConfirmado(), u.getCreadoEn(),
                new PerfilResumen(p.getNit(), p.getDpi(), p.getTelefono(), p.getDireccion(), p.isCompleto()));
    }
}
