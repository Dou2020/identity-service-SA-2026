package gt.cunoc.sa.identity.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuario")
public class UsuarioEntity extends ConIdAsignado {

    @Id
    private UUID id;

    // Siempre en minúsculas (normalizarCorreo): el UNIQUE de la columna distingue mayúsculas.
    @Column(nullable = false, length = 150, unique = true)
    private String correo;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "nombre_completo", nullable = false, length = 150)
    private String nombreCompleto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Rol rol;

    // LAZY: el alcance por sucursal solo compara el id, y leer getSucursal().getId()
    // no inicializa el proxy.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sucursal_id")
    private SucursalEntity sucursal;

    // Referencia por id a fleet.chofer (otra base, sin FK). Solo cuentas DRIVER.
    @Column(name = "chofer_id", unique = true)
    private UUID choferId;

    // aggregateVersion del último ChoferGuardado (E5) aplicado.
    @Column(name = "chofer_version")
    private Long choferVersion;

    @Column(nullable = false)
    private boolean activo;

    // NULL mientras la cuenta está pendiente: se llena al definir la primera contraseña.
    @Column(name = "confirmado_en")
    private Instant confirmadoEn;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected UsuarioEntity() {
    }

    private UsuarioEntity(String correo, String passwordHash, String nombreCompleto, Rol rol, SucursalEntity sucursal,
            Instant ahora) {
        validarSucursal(rol, sucursal);
        this.id = UUID.randomUUID();
        this.correo = normalizarCorreo(correo);
        this.passwordHash = passwordHash;
        this.nombreCompleto = nombreCompleto.strip();
        this.rol = rol;
        this.sucursal = sucursal;
        this.activo = true;
        this.creadoEn = ahora;
        this.actualizadoEn = ahora;
    }

    /**
     * Cuenta que todavía no define su contraseña: guarda un hash que no corresponde a ninguna
     * contraseña conocida (la columna es NOT NULL) y espera el enlace de bienvenida.
     */
    public static UsuarioEntity pendiente(String correo, String nombreCompleto, Rol rol, SucursalEntity sucursal,
            String hashInutilizable, Instant ahora) {
        return new UsuarioEntity(correo, hashInutilizable, nombreCompleto, rol, sucursal, ahora);
    }

    /** Cuenta que nace con contraseña: solo el SYS_ADMIN inicial (variables de entorno). */
    public static UsuarioEntity confirmado(String correo, String nombreCompleto, Rol rol, SucursalEntity sucursal,
            String passwordHash, Instant ahora) {
        UsuarioEntity usuario = new UsuarioEntity(correo, passwordHash, nombreCompleto, rol, sucursal, ahora);
        usuario.confirmadoEn = ahora;
        return usuario;
    }

    /** Cuenta DRIVER de un chofer de fleet (ChoferGuardado, E5). */
    public static UsuarioEntity pendienteDeChofer(UUID choferId, long choferVersion, String correo,
            String nombreCompleto, SucursalEntity sucursal, String hashInutilizable, Instant ahora) {
        UsuarioEntity usuario = new UsuarioEntity(correo, hashInutilizable, nombreCompleto, Rol.DRIVER, sucursal,
                ahora);
        usuario.choferId = choferId;
        usuario.choferVersion = choferVersion;
        return usuario;
    }

    public static String normalizarCorreo(String correo) {
        return correo == null ? null : correo.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Guarda la contraseña y, si es la primera, confirma la cuenta.
     *
     * @return true si la cuenta se acaba de confirmar (hay que emitir PerfilGuardado)
     */
    public boolean definirContrasena(String passwordHash, Instant ahora) {
        this.passwordHash = passwordHash;
        this.actualizadoEn = ahora;
        if (confirmadoEn != null) {
            return false;
        }
        confirmadoEn = ahora;
        return true;
    }

    public void cambiarRol(Rol rol, SucursalEntity sucursal, Instant ahora) {
        validarSucursal(rol, sucursal);
        this.rol = rol;
        this.sucursal = sucursal;
        this.actualizadoEn = ahora;
    }

    /** @return true si cambió el estado */
    public boolean cambiarEstado(boolean activo, Instant ahora) {
        if (this.activo == activo) {
            return false;
        }
        this.activo = activo;
        this.actualizadoEn = ahora;
        return true;
    }

    /** @return true si cambió */
    public boolean cambiarNombre(String nombreCompleto, Instant ahora) {
        String nuevo = nombreCompleto.strip();
        if (nuevo.equals(this.nombreCompleto)) {
            return false;
        }
        this.nombreCompleto = nuevo;
        this.actualizadoEn = ahora;
        return true;
    }

    /**
     * Aplica un ChoferGuardado más nuevo que el último aplicado.
     *
     * @return true si hay que cerrar las sesiones abiertas (cambió la sucursal o se desactivó)
     */
    public boolean aplicarChofer(long version, String correo, String nombreCompleto, SucursalEntity sucursal,
            boolean activo, Instant ahora) {
        boolean cambiaSucursal = !Objects.equals(this.sucursal.getId(), sucursal.getId());
        boolean seDesactiva = this.activo && !activo;
        if (correo != null) {
            this.correo = normalizarCorreo(correo);
        }
        this.nombreCompleto = nombreCompleto.strip();
        this.sucursal = sucursal;
        this.activo = activo;
        this.choferVersion = version;
        this.actualizadoEn = ahora;
        return cambiaSucursal || seDesactiva;
    }

    public boolean aceptaVersionDeChofer(long version) {
        return choferVersion == null || version > choferVersion;
    }

    private static void validarSucursal(Rol rol, SucursalEntity sucursal) {
        if (rol.requiereSucursal() != (sucursal != null)) {
            throw new IllegalArgumentException(rol.requiereSucursal()
                    ? "El rol " + rol + " necesita una sucursal"
                    : "El rol " + rol + " no pertenece a una sucursal");
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getCorreo() {
        return correo;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getNombreCompleto() {
        return nombreCompleto;
    }

    public Rol getRol() {
        return rol;
    }

    public SucursalEntity getSucursal() {
        return sucursal;
    }

    public UUID getSucursalId() {
        return sucursal == null ? null : sucursal.getId();
    }

    public UUID getChoferId() {
        return choferId;
    }

    public Long getChoferVersion() {
        return choferVersion;
    }

    public boolean isActivo() {
        return activo;
    }

    public Instant getConfirmadoEn() {
        return confirmadoEn;
    }

    public boolean isConfirmado() {
        return confirmadoEn != null;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
