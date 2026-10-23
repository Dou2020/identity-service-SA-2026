package gt.cunoc.sa.identity.infrastructure.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import gt.cunoc.sa.identity.exception.ServicioNoDisponibleException;
import gt.cunoc.sa.identity.infrastructure.security.PropiedadesJwt;

/**
 * Revocación de JWT (README, "Revocación de JWT"). Las escribe identity y las lee el gateway en
 * cada request, así que el formato de las llaves es un contrato entre los dos.
 *
 * Se llaman al final del caso de uso, antes del commit: si Redis falla, la excepción revierte
 * la transacción y no cambia nada (503). Si lo que falla es el commit, el usuario solo tiene
 * que volver a iniciar sesión, que es el error inofensivo.
 */
@Component
public class Sesiones {

    public static final String REVOCADO = "auth:revocado:";
    public static final String VALIDO_DESDE = "auth:valido-desde:";

    private final StringRedisTemplate redis;
    private final Duration duracionToken;

    public Sesiones(StringRedisTemplate redis, PropiedadesJwt jwt) {
        this.redis = redis;
        this.duracionToken = jwt.duracion();
    }

    /** Cierre de sesión: solo ese token. Vive lo que le falta al token y luego sobra. */
    public void revocar(String jti, Instant expiraEn) {
        Duration resto = Duration.between(Instant.now(), expiraEn);
        if (resto.isNegative() || resto.isZero()) {
            return;
        }
        ejecutar(() -> redis.opsForValue().set(REVOCADO + jti, "1", resto));
    }

    /**
     * Cierra todas las sesiones del usuario: el gateway rechaza los tokens con iat anterior al
     * corte. En segundos porque iat tiene precisión de segundos: con milisegundos, un login en
     * el mismo segundo del corte quedaría rechazado durante 8 h. Basta con que dure lo que un
     * token: después ya no queda ninguno anterior vigente.
     */
    public void cortar(UUID usuarioId) {
        String corte = Long.toString(Instant.now().getEpochSecond());
        ejecutar(() -> redis.opsForValue().set(VALIDO_DESDE + usuarioId, corte, duracionToken));
    }

    static void ejecutar(Runnable operacion) {
        try {
            operacion.run();
        } catch (DataAccessException e) {
            throw new ServicioNoDisponibleException("Redis no respondió", e);
        }
    }
}
