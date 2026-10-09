package gt.cunoc.sa.identity.api.interno;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gt.cunoc.sa.identity.api.error.ErrorDeNegocio;
import gt.cunoc.sa.identity.api.interno.dto.AdministradorResponse;
import gt.cunoc.sa.identity.api.interno.dto.UsuarioNombreResponse;
import gt.cunoc.sa.identity.api.sucursal.SucursalRepository;
import gt.cunoc.sa.identity.api.usuario.UsuarioRepository;

/**
 * S7, la interfaz DirectorioUsuarios (DIAGRAMAS.md §5): notification pregunta a quién enviar el
 * recordatorio de licencia. Se consulta al momento de enviar porque los administradores cambian.
 */
@Service
public class DirectorioService {

    private final UsuarioRepository usuarios;
    private final SucursalRepository sucursales;

    public DirectorioService(UsuarioRepository usuarios, SucursalRepository sucursales) {
        this.usuarios = usuarios;
        this.sucursales = sucursales;
    }

    @Transactional(readOnly = true)
    public List<AdministradorResponse> administradoresDe(UUID sucursalId) {
        if (!sucursales.existsById(sucursalId)) {
            throw ErrorDeNegocio.noEncontrado("No existe esa sucursal");
        }
        return usuarios.adminsActivosDeSucursal(sucursalId).stream()
                .map(u -> new AdministradorResponse(u.getId(), u.getNombreCompleto(), u.getCorreo()))
                .toList();
    }

    @Transactional(readOnly = true)
    public UsuarioNombreResponse usuario(UUID id) {
        var usuario = usuarios.findById(id)
                .orElseThrow(() -> ErrorDeNegocio.noEncontrado("No existe ese usuario"));
        return new UsuarioNombreResponse(usuario.getNombreCompleto());
    }
}
