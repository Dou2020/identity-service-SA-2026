package gt.cunoc.sa.identity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "sucursal")
public class SucursalEntity extends ConIdAsignado {

    @Id
    private UUID id;

    @Column(nullable = false, length = 10, unique = true)
    private String codigo;

    @Column(nullable = false, length = 100, unique = true)
    private String nombre;

    @Column(nullable = false, length = 50)
    private String departamento;

    @Column(nullable = false, length = 50)
    private String municipio;

    @Column(nullable = false, length = 200)
    private String direccion;

    @Column(length = 15)
    private String telefono;

    // BigDecimal y no double: la columna es numeric(9,6) y el mapa necesita las
    // coordenadas exactas, sin redondeo binario.
    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitud;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal longitud;

    @Column(nullable = false)
    private boolean activa;

    // @Version la incrementa en cada cambio: sirve a la vez de bloqueo optimista y
    // de aggregateVersion de SucursalGuardada (E1).
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "creado_en", nullable = false, updatable = false)
    private Instant creadoEn;

    protected SucursalEntity() {
    }

    public static SucursalEntity nueva(DatosSucursal datos, Instant ahora) {
        SucursalEntity sucursal = new SucursalEntity();
        sucursal.id = UUID.randomUUID();
        sucursal.activa = true;
        sucursal.creadoEn = ahora;
        sucursal.actualizar(datos);
        return sucursal;
    }

    public void actualizar(DatosSucursal datos) {
        this.codigo = datos.codigo().strip().toUpperCase(Locale.ROOT);
        this.nombre = datos.nombre().strip();
        this.departamento = datos.departamento().strip();
        this.municipio = datos.municipio().strip();
        this.direccion = datos.direccion().strip();
        this.telefono = datos.telefono() == null || datos.telefono().isBlank() ? null : datos.telefono().strip();
        this.latitud = datos.latitud();
        this.longitud = datos.longitud();
    }

    /** @return true si cambió el estado */
    public boolean cambiarEstado(boolean activa) {
        if (this.activa == activa) {
            return false;
        }
        this.activa = activa;
        return true;
    }

    /** Lo que un SYS_ADMIN escribe de una sucursal; el estado y la versión los maneja la entidad. */
    public record DatosSucursal(String codigo, String nombre, String departamento, String municipio,
            String direccion, String telefono, BigDecimal latitud, BigDecimal longitud) {
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNombre() {
        return nombre;
    }

    public String getDepartamento() {
        return departamento;
    }

    public String getMunicipio() {
        return municipio;
    }

    public String getDireccion() {
        return direccion;
    }

    public String getTelefono() {
        return telefono;
    }

    public BigDecimal getLatitud() {
        return latitud;
    }

    public BigDecimal getLongitud() {
        return longitud;
    }

    public boolean isActiva() {
        return activa;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
