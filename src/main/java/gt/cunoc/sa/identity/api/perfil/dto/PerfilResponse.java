package gt.cunoc.sa.identity.api.perfil.dto;

import java.util.UUID;

import gt.cunoc.sa.identity.domain.PerfilEntity;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.UsuarioEntity;

/** @param completo false si falta DPI, teléfono o dirección: el frontend pide completarlo */
public record PerfilResponse(
        UUID usuarioId,
        String correo,
        String nombreCompleto,
        Rol rol,
        UUID sucursalId,
        String choferId,
        String nit,
        String dpi,
        String telefono,
        String direccion,
        boolean completo) {

    public static PerfilResponse de(PerfilEntity p) {
        UsuarioEntity u = p.getUsuario();
        return new PerfilResponse(u.getId(), u.getCorreo(), u.getNombreCompleto(), u.getRol(), u.getSucursalId(),
                u.getChoferId(), p.getNit(), p.getDpi(), p.getTelefono(), p.getDireccion(), p.isCompleto());
    }
}
