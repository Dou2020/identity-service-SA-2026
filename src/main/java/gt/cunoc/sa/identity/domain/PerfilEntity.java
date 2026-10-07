package gt.cunoc.sa.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Composición de Usuario: comparte su id (@MapsId) y no existe sin él.
 *
 * DPI, teléfono y dirección pueden faltar en las cuentas que crea un admin, en las DRIVER
 * (E5) y en el admin inicial: el perfil queda incompleto hasta que el usuario lo llena. El
 * autorregistro los exige.
 */
@Entity
@Table(name = "perfil")
public class PerfilEntity extends ConIdAsignado {

    @Id
    @Column(name = "usuario_id")
    private UUID usuarioId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id")
    private UsuarioEntity usuario;

    // Normalizado; único entre cuentas salvo 'CF' (índice parcial perfil_nit_uq).
    @Column(nullable = false, length = 15)
    private String nit;

    @Column(length = 13, unique = true)
    private String dpi;

    @Column(length = 15)
    private String telefono;

    @Column(length = 200)
    private String direccion;

    // aggregateVersion de PerfilGuardado (E4). No es @Version: solo sube cuando el
    // perfil cambia, no con cada escritura del usuario.
    @Column(nullable = false)
    private long version;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    protected PerfilEntity() {
    }

    /** @param nit ya normalizado (Nit.normalizar) */
    public PerfilEntity(UsuarioEntity usuario, String nit, String dpi, String telefono, String direccion,
            Instant ahora) {
        this.usuario = usuario;
        this.nit = nit;
        this.dpi = vacioANulo(dpi);
        this.telefono = vacioANulo(telefono);
        this.direccion = vacioANulo(direccion);
        this.version = 0;
        this.actualizadoEn = ahora;
    }

    /**
     * @param nit ya normalizado
     * @return true si algo cambió; quien llama decide si eso amerita una versión nueva
     */
    public boolean actualizar(String nit, String dpi, String telefono, String direccion, Instant ahora) {
        dpi = vacioANulo(dpi);
        telefono = vacioANulo(telefono);
        direccion = vacioANulo(direccion);
        boolean cambio = !Objects.equals(this.nit, nit) || !Objects.equals(this.dpi, dpi)
                || !Objects.equals(this.telefono, telefono) || !Objects.equals(this.direccion, direccion);
        if (cambio) {
            this.nit = nit;
            this.dpi = dpi;
            this.telefono = telefono;
            this.direccion = direccion;
            this.actualizadoEn = ahora;
        }
        return cambio;
    }

    /** Sube el aggregateVersion antes de emitir un PerfilGuardado. */
    public long nuevaVersion(Instant ahora) {
        version++;
        actualizadoEn = ahora;
        return version;
    }

    public boolean isCompleto() {
        return dpi != null && telefono != null && direccion != null;
    }

    private static String vacioANulo(String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }

    @Override
    public UUID getId() {
        return usuarioId;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public UsuarioEntity getUsuario() {
        return usuario;
    }

    public String getNit() {
        return nit;
    }

    public String getDpi() {
        return dpi;
    }

    public String getTelefono() {
        return telefono;
    }

    public String getDireccion() {
        return direccion;
    }

    public long getVersion() {
        return version;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
