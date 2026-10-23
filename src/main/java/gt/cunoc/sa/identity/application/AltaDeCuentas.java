package gt.cunoc.sa.identity.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import gt.cunoc.sa.identity.api.perfil.PerfilRepository;
import gt.cunoc.sa.identity.api.usuario.UsuarioRepository;
import gt.cunoc.sa.identity.domain.Nit;
import gt.cunoc.sa.identity.domain.PerfilEntity;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.exception.ErrorDeNegocio;
import gt.cunoc.sa.identity.infrastructure.messaging.Outbox;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.UsuarioCreado;
import gt.cunoc.sa.identity.infrastructure.redis.Enlaces;
import gt.cunoc.sa.identity.infrastructure.redis.Enlaces.EnlaceEmitido;

/**
 * Lo común a toda cuenta nueva, venga del autorregistro, de un admin o de un ChoferGuardado:
 * nace pendiente, sin contraseña, y recibe por correo (E3) un enlace de 72 h para definirla.
 * Ninguna contraseña viaja por correo, y el enlace comprueba que el correo es de quien se registra.
 */
@Component
public class AltaDeCuentas {

    private final UsuarioRepository usuarios;
    private final PerfilRepository perfiles;
    private final Enlaces enlaces;
    private final Outbox outbox;
    private final PasswordEncoder codificador;
    private final PropiedadesIdentidad propiedades;
    private final SecureRandom aleatorio = new SecureRandom();

    public AltaDeCuentas(UsuarioRepository usuarios, PerfilRepository perfiles, Enlaces enlaces, Outbox outbox,
            PasswordEncoder codificador, PropiedadesIdentidad propiedades) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.enlaces = enlaces;
        this.outbox = outbox;
        this.codificador = codificador;
        this.propiedades = propiedades;
    }

    /** Datos de una cuenta nueva; el NIT llega sin normalizar y vacío significa CF. */
    public record NuevaCuenta(String correo, String nombreCompleto, Rol rol, SucursalEntity sucursal,
            String nit, String dpi, String telefono, String direccion) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UsuarioEntity crearPendiente(NuevaCuenta cuenta) {
        String correo = UsuarioEntity.normalizarCorreo(cuenta.correo());
        String nit = Nit.normalizar(cuenta.nit());
        verificarDisponibles(correo, nit, cuenta.dpi());
        UsuarioEntity usuario = UsuarioEntity.pendiente(correo, cuenta.nombreCompleto(), cuenta.rol(),
                cuenta.sucursal(), hashInutilizable(), Instant.now());
        return registrar(usuario, nit, cuenta.dpi(), cuenta.telefono(), cuenta.direccion());
    }

    /**
     * Guarda una cuenta pendiente ya armada con su perfil, emite el enlace de bienvenida y
     * agrega E3 a la outbox.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UsuarioEntity registrar(UsuarioEntity usuario, String nitNormalizado, String dpi, String telefono,
            String direccion) {
        usuarios.save(usuario);
        perfiles.save(new PerfilEntity(usuario, nitNormalizado, dpi, telefono, direccion, Instant.now()));
        // Las restricciones únicas fallan aquí y no en el commit: así no queda en Redis un
        // enlace de una cuenta que no se guardó.
        usuarios.flush();
        EnlaceEmitido enlace = enlaces.emitir(usuario.getId(), propiedades.enlaces().bienvenida());
        // aggregateVersion 0: la cuenta no tiene versión; a notification solo le importa el eventId.
        outbox.registrar(TiposEvento.USUARIO_CREADO, usuario.getId().toString(), 0,
                new UsuarioCreado(usuario.getId(), usuario.getCorreo(), usuario.getNombreCompleto(),
                        usuario.getRol(), usuario.getSucursalId(), enlace.token(), enlace.expiraEn()));
        return usuario;
    }

    /**
     * Mensajes claros para el caso común. Dos registros simultáneos con el mismo dato pasan los
     * dos por aquí; el árbitro es la restricción única de la base (ManejadorErrores → 409).
     */
    public void verificarDisponibles(String correoNormalizado, String nitNormalizado, String dpi) {
        if (usuarios.existsByCorreo(correoNormalizado)) {
            throw ErrorDeNegocio.conflicto("Ya existe una cuenta con ese correo");
        }
        if (!Nit.esConsumidorFinal(nitNormalizado) && perfiles.existsByNit(nitNormalizado)) {
            throw ErrorDeNegocio.conflicto("Ese NIT ya pertenece a otra cuenta");
        }
        if (dpi != null && !dpi.isBlank() && perfiles.existsByDpi(dpi.strip())) {
            throw ErrorDeNegocio.conflicto("Ya existe una cuenta con ese DPI");
        }
    }

    /** Hash BCrypt de 32 bytes aleatorios que nadie conoce: ninguna contraseña coincide con él. */
    public String hashInutilizable() {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        return codificador.encode(Base64.getEncoder().encodeToString(bytes));
    }
}
