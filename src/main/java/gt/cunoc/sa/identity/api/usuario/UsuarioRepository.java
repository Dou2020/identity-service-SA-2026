package gt.cunoc.sa.identity.api.usuario;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import jakarta.persistence.LockModeType;

public interface UsuarioRepository extends JpaRepository<UsuarioEntity, UUID>, JpaSpecificationExecutor<UsuarioEntity> {

    Optional<UsuarioEntity> findByCorreo(String correo);

    boolean existsByCorreo(String correo);

    boolean existsByRol(Rol rol);

    Optional<UsuarioEntity> findByChoferId(UUID choferId);

    /** Rol y sucursal sin cargar la entidad: deciden qué filas bloquear antes de leerla. */
    @Query("select u.rol as rol, u.sucursal.id as sucursalId from UsuarioEntity u where u.id = :id")
    Optional<RolYSucursal> rolYSucursal(UUID id);

    /**
     * Invariante "siempre hay un SYS_ADMIN activo": bloquea a todos los que cuentan antes de
     * contarlos. Ordenado por id para que dos transacciones tomen los candados en el mismo
     * orden (sin interbloqueo); la segunda espera, y al seguir PostgreSQL vuelve a evaluar el
     * WHERE, así que ya no ve al que la primera desactivó.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from UsuarioEntity u
            where u.rol = gt.cunoc.sa.identity.domain.Rol.SYS_ADMIN and u.activo = true and u.confirmadoEn is not null
            order by u.id""")
    List<UsuarioEntity> bloquearSysAdminsActivos();

    /**
     * Administradores activos de una sucursal, ordenados para el directorio interno.
     */
    @Query("""
            select u from UsuarioEntity u
            where u.rol = gt.cunoc.sa.identity.domain.Rol.BRANCH_ADMIN and u.activo = true and u.sucursal.id = :sucursalId
            order by u.nombreCompleto""")
    List<UsuarioEntity> adminsActivosDeSucursal(UUID sucursalId);

    /**
     * Autorregistros que nadie confirmó: nunca iniciaron sesión ni salió su PerfilGuardado, así
     * que ningún otro servicio los conoce. El perfil cae por el ON DELETE CASCADE.
     */
    @Modifying
    @Query("""
            delete from UsuarioEntity u
            where u.rol = gt.cunoc.sa.identity.domain.Rol.CUSTOMER and u.confirmadoEn is null and u.creadoEn < :limite""")
    int borrarRegistrosSinConfirmar(Instant limite);

    interface RolYSucursal {
        Rol getRol();

        UUID getSucursalId();
    }
}
