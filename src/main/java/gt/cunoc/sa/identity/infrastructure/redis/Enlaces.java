package gt.cunoc.sa.identity.infrastructure.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import gt.cunoc.sa.identity.api.error.ServicioNoDisponibleException;

/**
 * Tokens de un solo uso para definir o recuperar la contraseña (README, "Recuperación y
 * definición de contraseña"). En Redis se guarda el SHA-256 del token, nunca el token: un
 * volcado de Redis o del AOF no deja enlaces usables.
 */
@Component
public class Enlaces {

    public static final String RESTABLECER = "auth:restablecer:";
    public static final String RESTABLECER_USUARIO = "auth:restablecer-usuario:";
    public static final String ESPERA_RECUPERACION = "auth:recuperacion-espera:";

    private static final Duration ESPERA = Duration.ofSeconds(60);
    private static final int BYTES_TOKEN = 32;   // 256 bits
    // Base64 URL sin relleno de 32 bytes: 43 caracteres.
    private static final Pattern FORMATO_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /**
     * Solo vale el último enlace: borrar el anterior y guardar el nuevo tiene que ser atómico,
     * o dos solicitudes simultáneas podrían dejar dos enlaces vigentes. Lua corre entero sin
     * que otro comando se intercale.
     */
    private static final RedisScript<Long> EMITIR = RedisScript.of("""
            local anterior = redis.call('GET', KEYS[1])
            if anterior then
                redis.call('DEL', ARGV[4] .. anterior)
            end
            redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SecureRandom aleatorio = new SecureRandom();

    public Enlaces(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record EnlaceEmitido(String token, Instant expiraEn) {
    }

    /** Genera un enlace nuevo para el usuario e invalida el que tuviera. */
    public EnlaceEmitido emitir(UUID usuarioId, Duration vida) {
        byte[] bytes = new byte[BYTES_TOKEN];
        aleatorio.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256(token);
        Instant expiraEn = Instant.now().plus(vida);
        Sesiones.ejecutar(() -> redis.execute(EMITIR,
                List.of(RESTABLECER_USUARIO + usuarioId, RESTABLECER + hash),
                hash, usuarioId.toString(), Long.toString(vida.toSeconds()), RESTABLECER));
        return new EnlaceEmitido(token, expiraEn);
    }

    /**
     * Consume el token: GETDEL es atómico, así que de dos clics simultáneos solo uno obtiene
     * el usuario.
     *
     * @return el usuario del enlace, o vacío si no existe, venció o ya se usó
     */
    public Optional<UUID> consumir(String token) {
        if (token == null || !FORMATO_TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        String usuarioId;
        try {
            usuarioId = redis.opsForValue().getAndDelete(RESTABLECER + sha256(token));
        } catch (DataAccessException e) {
            throw new ServicioNoDisponibleException("Redis no respondió", e);
        }
        return Optional.ofNullable(usuarioId).map(UUID::fromString);
    }

    /**
     * Como máximo un correo de recuperación por minuto a cada dirección. Va antes de buscar al
     * usuario para que todos los correos dependan de Redis por igual: si Redis falla, el 503
     * no revela si la cuenta existe.
     *
     * @return false si ya se pidió uno hace menos de un minuto
     */
    public boolean reservarEsperaRecuperacion(String correoNormalizado) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(ESPERA_RECUPERACION + sha256(correoNormalizado), "1", ESPERA));
        } catch (DataAccessException e) {
            throw new ServicioNoDisponibleException("Redis no respondió", e);
        }
    }

    public static String sha256(String valor) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
