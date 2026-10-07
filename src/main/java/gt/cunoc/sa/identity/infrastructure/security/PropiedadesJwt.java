package gt.cunoc.sa.identity.infrastructure.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param secreto  JWT_SECRET en Base64; lo comparten solo identity (firma) y el gateway (verifica)
 * @param duracion vida del token (8 h, una jornada; no hay refresh token)
 */
@ConfigurationProperties("jwt")
public record PropiedadesJwt(String secreto, Duration duracion) {
}
