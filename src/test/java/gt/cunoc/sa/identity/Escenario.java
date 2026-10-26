package gt.cunoc.sa.identity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import gt.cunoc.sa.identity.api.perfil.PerfilRepository;
import gt.cunoc.sa.identity.api.sucursal.SucursalRepository;
import gt.cunoc.sa.identity.api.usuario.UsuarioRepository;
import gt.cunoc.sa.identity.domain.Nit;
import gt.cunoc.sa.identity.domain.PerfilEntity;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.SucursalEntity.DatosSucursal;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;

/**
 * Datos de prueba escritos directo en la base (sin eventos): cada prueba arma lo suyo con
 * correos, NIT y códigos únicos, porque todas comparten la misma base.
 */
@TestComponent
public class Escenario {

    public static final String ADMIN_INICIAL = "admin@prueba.gt";
    public static final String CONTRASENA = "Clave12345";

    private static final AtomicInteger CONTADOR = new AtomicInteger(ThreadLocalRandom.current().nextInt(100_000));

    private final UsuarioRepository usuarios;
    private final PerfilRepository perfiles;
    private final SucursalRepository sucursales;
    private final PasswordEncoder codificador;
    private final TransactionTemplate transaccion;

    public Escenario(UsuarioRepository usuarios, PerfilRepository perfiles, SucursalRepository sucursales,
            PasswordEncoder codificador, TransactionTemplate transaccion) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.sucursales = sucursales;
        this.codificador = codificador;
        this.transaccion = transaccion;
    }

    public SucursalEntity sucursal() {
        int n = CONTADOR.incrementAndGet();
        return transaccion.execute(estado -> sucursales.save(SucursalEntity.nueva(new DatosSucursal("T" + n,
                "Sucursal de prueba " + n, "Quetzaltenango", "Quetzaltenango", "Zona 1", null,
                new BigDecimal("14.834999"), new BigDecimal("-91.518611")), Instant.now())));
    }

    /** Cuenta confirmada, con contraseña CONTRASENA. */
    public UsuarioEntity usuario(Rol rol, SucursalEntity sucursal) {
        return transaccion.execute(estado -> {
            UsuarioEntity usuario = UsuarioEntity.confirmado(correo(), "Usuario " + rol, rol, sucursal,
                    codificador.encode(CONTRASENA), Instant.now());
            guardar(usuario);
            return usuario;
        });
    }

    /** Cuenta pendiente (sin contraseña definida). */
    public UsuarioEntity pendiente(Rol rol, SucursalEntity sucursal) {
        return transaccion.execute(estado -> {
            UsuarioEntity usuario = UsuarioEntity.pendiente(correo(), "Pendiente " + rol, rol, sucursal,
                    codificador.encode(UUID.randomUUID().toString()), Instant.now());
            guardar(usuario);
            return usuario;
        });
    }

    private void guardar(UsuarioEntity usuario) {
        usuarios.save(usuario);
        perfiles.save(new PerfilEntity(usuario, Nit.CONSUMIDOR_FINAL, null, "55551234", null, Instant.now()));
    }

    public UsuarioActual admin() {
        return actual(usuarios.findByCorreo(ADMIN_INICIAL).orElseThrow());
    }

    /** El principal que armaría EncabezadosGatewayFilter con los X-User-* del token de este usuario. */
    public static UsuarioActual actual(UsuarioEntity u) {
        UUID sucursal = u.getRol() == Rol.BRANCH_ADMIN || u.getRol() == Rol.CASHIER ? u.getSucursalId() : null;
        return new UsuarioActual(u.getId(), sucursal, u.getChoferId(), Set.of(u.getRol().name()));
    }

    /** Los encabezados que escribe el gateway. */
    public static RequestPostProcessor como(UsuarioActual actual) {
        return request -> {
            request.addHeader("X-User-Id", actual.usuarioId().toString());
            request.addHeader("X-User-Roles", String.join(",", actual.roles()));
            if (actual.sucursalId() != null) {
                request.addHeader("X-Sucursal-Id", actual.sucursalId().toString());
            }
            if (actual.choferId() != null) {
                request.addHeader("X-Chofer-Id", actual.choferId());
            }
            return request;
        };
    }

    public static RequestPostProcessor como(UsuarioEntity usuario) {
        return como(actual(usuario));
    }

    /** Para llamar casos de uso con @PreAuthorize fuera de un request (pruebas con hilos). */
    public static void autenticar(UsuarioActual actual) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                actual, null, actual.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    public static String correo() {
        return "u" + CONTADOR.incrementAndGet() + "-" + ThreadLocalRandom.current().nextInt(1_000_000) + "@prueba.gt";
    }

    /** Un NIT de 8 dígitos al azar. */
    public static String nit() {
        return Integer.toString(10_000_000 + ThreadLocalRandom.current().nextInt(90_000_000));
    }

    public static String dpi() {
        return Long.toString(1_000_000_000_000L + ThreadLocalRandom.current().nextLong(9_000_000_000_000L));
    }
}
