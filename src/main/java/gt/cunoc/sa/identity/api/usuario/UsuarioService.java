package gt.cunoc.sa.identity.api.usuario;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gt.cunoc.sa.identity.api.error.ErrorDeNegocio;
import gt.cunoc.sa.identity.api.perfil.PerfilRepository;
import gt.cunoc.sa.identity.api.sucursal.SucursalRepository;
import gt.cunoc.sa.identity.api.usuario.UsuarioRepository.RolYSucursal;
import gt.cunoc.sa.identity.api.usuario.dto.CambiarUsuarioRequest;
import gt.cunoc.sa.identity.api.usuario.dto.CrearUsuarioRequest;
import gt.cunoc.sa.identity.api.usuario.dto.UsuarioResponse;
import gt.cunoc.sa.identity.application.Alcance;
import gt.cunoc.sa.identity.application.AltaDeCuentas;
import gt.cunoc.sa.identity.application.AltaDeCuentas.NuevaCuenta;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.redis.Sesiones;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;
import jakarta.persistence.criteria.Predicate;

/**
 * Administración de usuarios. Las dos invariantes de identity se deciden aquí, con candados
 * de PostgreSQL: siempre hay un SYS_ADMIN activo y cada sucursal conserva un administrador.
 */
@Service
public class UsuarioService {

    /** Roles de personal: solo entre estos se cambia el rol. DRIVER lo gobierna fleet (E5) y CUSTOMER el registro. */
    private static final Set<Rol> PERSONAL = EnumSet.of(Rol.SYS_ADMIN, Rol.BRANCH_ADMIN, Rol.CASHIER);

    private final UsuarioRepository usuarios;
    private final PerfilRepository perfiles;
    private final SucursalRepository sucursales;
    private final AltaDeCuentas altas;
    private final Sesiones sesiones;

    public UsuarioService(UsuarioRepository usuarios, PerfilRepository perfiles, SucursalRepository sucursales,
            AltaDeCuentas altas, Sesiones sesiones) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.sucursales = sucursales;
        this.altas = altas;
        this.sesiones = sesiones;
    }

    /** Un BRANCH_ADMIN solo ve su sucursal: el filtro va en la consulta, no después. */
    @PreAuthorize("hasAnyRole('SYS_ADMIN', 'BRANCH_ADMIN')")
    @Transactional(readOnly = true)
    public Page<UsuarioResponse> listar(Rol rol, UUID sucursalId, Boolean activo, Pageable pagina, UsuarioActual actual) {
        UUID alcance = Alcance.esSysAdmin(actual) ? sucursalId : Alcance.sucursalDe(actual);
        Specification<UsuarioEntity> filtro = (raiz, consulta, cb) -> {
            List<Predicate> condiciones = new ArrayList<>();
            if (rol != null) {
                condiciones.add(cb.equal(raiz.get("rol"), rol));
            }
            if (alcance != null) {
                condiciones.add(cb.equal(raiz.get("sucursal").get("id"), alcance));
            }
            if (activo != null) {
                condiciones.add(cb.equal(raiz.get("activo"), activo));
            }
            return cb.and(condiciones.toArray(Predicate[]::new));
        };
        return usuarios.findAll(filtro, pagina).map(UsuarioResponse::de);
    }

    @PreAuthorize("hasAnyRole('SYS_ADMIN', 'BRANCH_ADMIN')")
    @Transactional(readOnly = true)
    public UsuarioResponse obtener(UUID id, UsuarioActual actual) {
        UsuarioEntity usuario = buscar(id);
        Alcance.exigirSucursal(actual, usuario.getSucursalId());
        return UsuarioResponse.de(usuario, perfiles.getReferenceById(id));
    }

    /**
     * SYS_ADMIN crea SYS_ADMIN o BRANCH_ADMIN; BRANCH_ADMIN crea CASHIER de su propia
     * sucursal. DRIVER nace de ChoferGuardado y CUSTOMER del autorregistro.
     */
    @PreAuthorize("hasAnyRole('SYS_ADMIN', 'BRANCH_ADMIN')")
    @Transactional
    public UsuarioResponse crear(CrearUsuarioRequest solicitud, UsuarioActual actual) {
        SucursalEntity sucursal;
        if (Alcance.esSysAdmin(actual)) {
            sucursal = switch (solicitud.rol()) {
                case SYS_ADMIN -> null;
                case BRANCH_ADMIN -> sucursalActiva(solicitud.sucursalId());
                default -> throw ErrorDeNegocio.noProcesable(
                        "Un administrador del sistema crea administradores del sistema o de sucursal");
            };
        } else {
            if (solicitud.rol() != Rol.CASHIER) {
                throw new AccessDeniedException("Un BRANCH_ADMIN solo crea cajeros");
            }
            // La del token, nunca la del body.
            sucursal = sucursalActiva(Alcance.sucursalDe(actual));
        }
        UsuarioEntity usuario = altas.crearPendiente(new NuevaCuenta(solicitud.correo(), solicitud.nombreCompleto(),
                solicitud.rol(), sucursal, solicitud.nit(), solicitud.dpi(), solicitud.telefono(),
                solicitud.direccion()));
        return UsuarioResponse.de(usuario, perfiles.getReferenceById(usuario.getId()));
    }

    /** Cambia rol o sucursal y cierra sus sesiones: el token viejo trae los claims viejos. */
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @Transactional
    public UsuarioResponse cambiarRol(UUID id, CambiarUsuarioRequest solicitud) {
        RolYSucursal antes = usuarios.rolYSucursal(id)
                .orElseThrow(() -> ErrorDeNegocio.noEncontrado("No existe ese usuario"));
        if (!PERSONAL.contains(antes.getRol()) || !PERSONAL.contains(solicitud.rol())) {
            throw ErrorDeNegocio.noProcesable(
                    "Solo se cambia el rol entre administrador del sistema, administrador de sucursal y cajero");
        }
        SucursalEntity nueva = solicitud.rol().requiereSucursal() ? sucursalActiva(solicitud.sucursalId()) : null;
        boolean sigueSysAdmin = solicitud.rol() == Rol.SYS_ADMIN;
        boolean sigueAdminDeSuSucursal = solicitud.rol() == Rol.BRANCH_ADMIN
                && Objects.equals(nueva == null ? null : nueva.getId(), antes.getSucursalId());
        verificarInvariantes(id, antes, sigueSysAdmin, sigueAdminDeSuSucursal);

        UsuarioEntity usuario = cargarSinCambios(id, antes);
        usuario.cambiarRol(solicitud.rol(), nueva, Instant.now());
        sesiones.cortar(id);
        return UsuarioResponse.de(usuario);
    }

    /**
     * Desactivar nunca borra (los usuarios no se eliminan) y cierra sus sesiones al instante.
     * Un BRANCH_ADMIN solo activa o desactiva a los cajeros de su sucursal.
     */
    @PreAuthorize("hasAnyRole('SYS_ADMIN', 'BRANCH_ADMIN')")
    @Transactional
    public UsuarioResponse cambiarEstado(UUID id, boolean activo, UsuarioActual actual) {
        RolYSucursal antes = usuarios.rolYSucursal(id)
                .orElseThrow(() -> ErrorDeNegocio.noEncontrado("No existe ese usuario"));
        if (!Alcance.esSysAdmin(actual)
                && (antes.getRol() != Rol.CASHIER || !Objects.equals(antes.getSucursalId(), Alcance.sucursalDe(actual)))) {
            throw new AccessDeniedException("Un BRANCH_ADMIN solo cambia el estado de sus cajeros");
        }
        if (antes.getRol() == Rol.DRIVER) {
            // Si identity lo cambiara, el siguiente ChoferGuardado lo revertiría.
            throw ErrorDeNegocio.conflicto("La cuenta de un chofer se activa o desactiva desde el registro del chofer");
        }
        if (!activo) {
            verificarInvariantes(id, antes, false, false);
        }
        UsuarioEntity usuario = cargarSinCambios(id, antes);
        if (usuario.cambiarEstado(activo, Instant.now()) && !activo) {
            sesiones.cortar(id);
        }
        return UsuarioResponse.de(usuario);
    }

    /**
     * Si el usuario deja de contar para una invariante, bloquea (SELECT … FOR UPDATE) a todos
     * los que cuentan y verifica que quede otro. Contar sin bloquear dejaría que dos
     * desactivaciones simultáneas vieran "quedan dos" y desactivaran a ambos.
     *
     * Se bloquea antes de cargar al usuario: si él está en el conjunto, la consulta lo carga
     * ya bloqueado y con su estado actual. Cada operación bloquea un solo conjunto, en orden de
     * id, así que dos operaciones no se interbloquean.
     */
    private void verificarInvariantes(UUID id, RolYSucursal antes, boolean sigueSysAdmin,
            boolean sigueAdminDeSuSucursal) {
        if (antes.getRol() == Rol.SYS_ADMIN && !sigueSysAdmin) {
            List<UsuarioEntity> activos = usuarios.bloquearSysAdminsActivos();
            if (esElUnico(id, activos)) {
                throw ErrorDeNegocio.conflicto("Debe quedar al menos un administrador del sistema activo");
            }
        }
        if (antes.getRol() == Rol.BRANCH_ADMIN && !sigueAdminDeSuSucursal) {
            List<UsuarioEntity> activos = usuarios.bloquearAdminsActivosDeSucursal(antes.getSucursalId());
            if (esElUnico(id, activos)) {
                throw ErrorDeNegocio.conflicto("La sucursal debe conservar al menos un administrador activo");
            }
        }
    }

    /** Si no está en el conjunto (inactivo, o SYS_ADMIN sin confirmar), quitarlo no cambia la cuenta. */
    private static boolean esElUnico(UUID id, List<UsuarioEntity> activos) {
        return activos.size() == 1 && activos.get(0).getId().equals(id);
    }

    /**
     * Los candados se eligieron con el rol y la sucursal leídos antes de bloquear; si cambiaron
     * mientras tanto (otro admin editó al mismo usuario), se protegió el conjunto equivocado.
     */
    private UsuarioEntity cargarSinCambios(UUID id, RolYSucursal antes) {
        UsuarioEntity usuario = buscar(id);
        if (usuario.getRol() != antes.getRol() || !Objects.equals(usuario.getSucursalId(), antes.getSucursalId())) {
            throw ErrorDeNegocio.conflicto("El usuario cambió mientras tanto. Vuelve a cargarlo e intenta de nuevo");
        }
        return usuario;
    }

    private UsuarioEntity buscar(UUID id) {
        return usuarios.findById(id).orElseThrow(() -> ErrorDeNegocio.noEncontrado("No existe ese usuario"));
    }

    private SucursalEntity sucursalActiva(UUID sucursalId) {
        if (sucursalId == null) {
            throw ErrorDeNegocio.noProcesable("Indica la sucursal");
        }
        SucursalEntity sucursal = sucursales.findById(sucursalId)
                .orElseThrow(() -> ErrorDeNegocio.noProcesable("No existe esa sucursal"));
        if (!sucursal.isActiva()) {
            throw ErrorDeNegocio.noProcesable("La sucursal está inactiva");
        }
        return sucursal;
    }
}
