package gt.cunoc.sa.identity.api.sucursal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gt.cunoc.sa.identity.api.perfil.PerfilRepository;
import gt.cunoc.sa.identity.api.sucursal.dto.CrearSucursalRequest;
import gt.cunoc.sa.identity.api.sucursal.dto.CrearSucursalRequest.NuevoAdministrador;
import gt.cunoc.sa.identity.api.sucursal.dto.DatosSucursalRequest;
import gt.cunoc.sa.identity.api.sucursal.dto.SucursalCreadaResponse;
import gt.cunoc.sa.identity.api.sucursal.dto.SucursalResponse;
import gt.cunoc.sa.identity.api.usuario.dto.UsuarioResponse;
import gt.cunoc.sa.identity.application.AltaDeCuentas;
import gt.cunoc.sa.identity.application.AltaDeCuentas.NuevaCuenta;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.exception.ErrorDeNegocio;
import gt.cunoc.sa.identity.infrastructure.messaging.Outbox;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.SucursalGuardada;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;

/**
 * Sucursales. Cada cambio emite SucursalGuardada (E1) con la versión de @Version, que fleet,
 * trip y finance usan para descartar una réplica vieja que llegue tarde.
 */
@Service
public class SucursalService {

    private final SucursalRepository sucursales;
    private final PerfilRepository perfiles;
    private final AltaDeCuentas altas;
    private final Outbox outbox;

    public SucursalService(SucursalRepository sucursales, PerfilRepository perfiles, AltaDeCuentas altas,
            Outbox outbox) {
        this.sucursales = sucursales;
        this.perfiles = perfiles;
        this.altas = altas;
        this.outbox = outbox;
    }

    /** Cualquier usuario autenticado: el cliente elige sucursal al pedir un alquiler. */
    @Transactional(readOnly = true)
    public List<SucursalResponse> listar(Boolean activa) {
        List<SucursalEntity> lista = activa == null
                ? sucursales.findAllByOrderByNombre()
                : sucursales.findByActivaOrderByNombre(activa);
        return lista.stream().map(SucursalResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public SucursalResponse obtener(UUID id) {
        return SucursalResponse.de(buscar(id));
    }

    /**
     * La sucursal y su primer administrador en una sola transacción: si el correo del admin ya
     * existe, tampoco se crea la sucursal. Así ninguna sucursal queda sin administrador.
     */
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @Transactional
    public SucursalCreadaResponse crear(CrearSucursalRequest solicitud) {
        SucursalEntity sucursal = SucursalEntity.nueva(solicitud.sucursal().aDominio(), Instant.now());
        // flush: un código o nombre repetido falla aquí (409), antes de emitir el enlace del admin.
        sucursales.saveAndFlush(sucursal);
        publicar(sucursal);
        NuevoAdministrador admin = solicitud.administrador();
        UsuarioEntity administrador = altas.crearPendiente(new NuevaCuenta(admin.correo(), admin.nombreCompleto(),
                Rol.BRANCH_ADMIN, sucursal, null, null, admin.telefono(), null));
        return new SucursalCreadaResponse(SucursalResponse.de(sucursal),
                UsuarioResponse.de(administrador, perfiles.getReferenceById(administrador.getId())));
    }

    @PreAuthorize("hasRole('SYS_ADMIN')")
    @Transactional
    public SucursalResponse actualizar(UUID id, DatosSucursalRequest datos) {
        SucursalEntity sucursal = buscar(id);
        sucursal.actualizar(datos.aDominio());
        sucursales.flush();   // @Version sube al hacer flush; E1 lleva la versión nueva
        publicar(sucursal);
        return SucursalResponse.de(sucursal);
    }

    /**
     * Desactivar no toca a sus usuarios ni a sus buses: los demás servicios reciben E1 y dejan
     * de ofrecer la sucursal para lo nuevo.
     */
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @Transactional
    public SucursalResponse cambiarEstado(UUID id, boolean activa) {
        SucursalEntity sucursal = buscar(id);
        if (sucursal.cambiarEstado(activa)) {
            sucursales.flush();
            publicar(sucursal);
        }
        return SucursalResponse.de(sucursal);
    }

    private void publicar(SucursalEntity s) {
        outbox.registrar(TiposEvento.SUCURSAL_GUARDADA, s.getId().toString(), s.getVersion(),
                new SucursalGuardada(s.getId(), s.getCodigo(), s.getNombre(), s.getDepartamento(), s.getMunicipio(),
                        s.getLatitud(), s.getLongitud(), s.isActiva()));
    }

    private SucursalEntity buscar(UUID id) {
        return sucursales.findById(id).orElseThrow(() -> ErrorDeNegocio.noEncontrado("No existe esa sucursal"));
    }
}
