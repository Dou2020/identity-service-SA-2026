package gt.cunoc.sa.identity.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gt.cunoc.sa.identity.api.auth.dto.LoginResponse;
import gt.cunoc.sa.identity.api.auth.dto.RegistroRequest;
import gt.cunoc.sa.identity.api.error.ErrorDeNegocio;
import gt.cunoc.sa.identity.api.perfil.PerfilRepository;
import gt.cunoc.sa.identity.api.perfil.PerfilService;
import gt.cunoc.sa.identity.api.usuario.UsuarioRepository;
import gt.cunoc.sa.identity.api.usuario.dto.UsuarioResponse;
import gt.cunoc.sa.identity.application.AltaDeCuentas;
import gt.cunoc.sa.identity.application.AltaDeCuentas.NuevaCuenta;
import gt.cunoc.sa.identity.application.PropiedadesIdentidad;
import gt.cunoc.sa.identity.domain.PerfilEntity;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.messaging.Outbox;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.RecuperacionSolicitada;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;
import gt.cunoc.sa.identity.infrastructure.redis.Enlaces;
import gt.cunoc.sa.identity.infrastructure.redis.Enlaces.EnlaceEmitido;
import gt.cunoc.sa.identity.infrastructure.redis.Sesiones;
import gt.cunoc.sa.identity.infrastructure.security.EmisorJwt;
import gt.cunoc.sa.identity.infrastructure.security.EmisorJwt.TokenEmitido;
import gt.cunoc.sa.identity.infrastructure.security.EmisorJwt.TokenLeido;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;

/**
 * Login, registro, recuperación y definición de contraseña, y cierre de sesión (README,
 * "Sesiones y límites con Redis"). Los cuatro primeros son públicos: el gateway no exige token
 * y los limita a 5 por minuto por IP.
 */
@Service
public class AutenticacionService {

    private static final String CREDENCIALES_INVALIDAS = "Correo o contraseña incorrectos";
    private static final String ENLACE_INVALIDO = "El enlace no es válido o ya venció. Solicita uno nuevo";

    private final UsuarioRepository usuarios;
    private final PerfilRepository perfiles;
    private final AltaDeCuentas altas;
    private final Enlaces enlaces;
    private final Sesiones sesiones;
    private final Outbox outbox;
    private final EmisorJwt emisor;
    private final PasswordEncoder codificador;
    private final PropiedadesIdentidad propiedades;
    /**
     * Se compara contra este hash cuando el correo no existe: así el login tarda lo mismo
     * exista o no la cuenta, y el tiempo de respuesta no revela qué correos están registrados.
     */
    private final String hashFicticio;

    public AutenticacionService(UsuarioRepository usuarios, PerfilRepository perfiles, AltaDeCuentas altas,
            Enlaces enlaces, Sesiones sesiones, Outbox outbox, EmisorJwt emisor, PasswordEncoder codificador,
            PropiedadesIdentidad propiedades) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.altas = altas;
        this.enlaces = enlaces;
        this.sesiones = sesiones;
        this.outbox = outbox;
        this.emisor = emisor;
        this.codificador = codificador;
        this.propiedades = propiedades;
        this.hashFicticio = codificador.encode(UUID.randomUUID().toString());
    }

    /**
     * Inactiva, pendiente o con la contraseña equivocada: la misma respuesta. Una cuenta
     * pendiente guarda un hash inutilizable, así que nunca coincide.
     */
    @Transactional(readOnly = true)
    public LoginResponse iniciarSesion(String correo, String contrasena) {
        Optional<UsuarioEntity> encontrado = usuarios.findByCorreo(UsuarioEntity.normalizarCorreo(correo));
        if (encontrado.isEmpty()) {
            codificador.matches(contrasena, hashFicticio);
            throw ErrorDeNegocio.noAutenticado(CREDENCIALES_INVALIDAS);
        }
        UsuarioEntity usuario = encontrado.get();
        if (!codificador.matches(contrasena, usuario.getPasswordHash()) || !usuario.isActivo()
                || !usuario.isConfirmado()) {
            throw ErrorDeNegocio.noAutenticado(CREDENCIALES_INVALIDAS);
        }
        TokenEmitido token = emisor.emitir(usuario);
        return new LoginResponse(token.token(), token.expiraEn(), UsuarioResponse.de(usuario));
    }

    /** CUSTOMER pendiente con su perfil completo; recibe el enlace de 72 h (E3). */
    @Transactional
    public UsuarioEntity registrar(RegistroRequest solicitud) {
        return altas.crearPendiente(new NuevaCuenta(solicitud.correo(), solicitud.nombreCompleto(), Rol.CUSTOMER,
                null, solicitud.nit(), solicitud.dpi(), solicitud.telefono(), solicitud.direccion()));
    }

    /**
     * Siempre termina igual (202) exista o no la cuenta. La espera de un minuto va primero,
     * antes de buscar al usuario: si Redis falla, todos los correos reciben el mismo 503.
     * Sirve también a una cuenta pendiente cuyo enlace de bienvenida venció.
     */
    @Transactional
    public void solicitarRecuperacion(String correo) {
        String normalizado = UsuarioEntity.normalizarCorreo(correo);
        if (!enlaces.reservarEsperaRecuperacion(normalizado)) {
            return;
        }
        usuarios.findByCorreo(normalizado).filter(UsuarioEntity::isActivo).ifPresent(usuario -> {
            EnlaceEmitido enlace = enlaces.emitir(usuario.getId(), propiedades.enlaces().recuperacion());
            outbox.registrar(TiposEvento.RECUPERACION_SOLICITADA, usuario.getId().toString(), 0,
                    new RecuperacionSolicitada(usuario.getId(), usuario.getCorreo(), usuario.getNombreCompleto(),
                            enlace.token(), enlace.expiraEn()));
        });
    }

    /**
     * El mismo para la bienvenida y la recuperación. GETDEL consume el token antes del commit:
     * si el commit falla después, el usuario solo pide otro enlace. El primer uso confirma la
     * cuenta y emite PerfilGuardado (E4), que es cuando customer se entera de que existe.
     */
    @Transactional
    public void definirContrasena(String token, String nueva) {
        if (nueva.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ErrorDeNegocio.solicitudInvalida("La contraseña es demasiado larga");
        }
        UUID usuarioId = enlaces.consumir(token)
                .orElseThrow(() -> ErrorDeNegocio.solicitudInvalida(ENLACE_INVALIDO));
        PerfilEntity perfil = perfiles.conUsuario(usuarioId)
                .filter(p -> p.getUsuario().isActivo())
                .orElseThrow(() -> ErrorDeNegocio.solicitudInvalida(ENLACE_INVALIDO));
        UsuarioEntity usuario = perfil.getUsuario();
        Instant ahora = Instant.now();
        if (usuario.definirContrasena(codificador.encode(nueva), ahora)) {
            long version = perfil.nuevaVersion(ahora);
            outbox.registrar(TiposEvento.PERFIL_GUARDADO, usuario.getId().toString(), version,
                    PerfilService.evento(perfil));
        }
        // Cierra las sesiones abiertas: si alguien más conocía la contraseña anterior, pierde el acceso.
        sesiones.cortar(usuarioId);
    }

    /** Revoca solo el token de esta sesión; el gateway lo rechaza desde el siguiente request. */
    public void cerrarSesion(String authorization, UsuarioActual actual) {
        TokenLeido token = Optional.ofNullable(authorization)
                .filter(a -> a.regionMatches(true, 0, "Bearer ", 0, 7))
                .flatMap(a -> emisor.leer(a.substring(7).strip()))
                .filter(t -> t.usuarioId().equals(actual.usuarioId()))
                .orElseThrow(() -> ErrorDeNegocio.noAutenticado("La sesión no es válida"));
        sesiones.revocar(token.jti(), token.expiraEn());
    }
}
