package gt.cunoc.sa.identity.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enlaces      vida de los enlaces de contraseña: 72 h la bienvenida, 30 min la recuperación
 * @param adminInicial primer SYS_ADMIN, desde ADMIN_CORREO y ADMIN_CONTRASENA
 */
@ConfigurationProperties("identity")
public record PropiedadesIdentidad(VidaEnlaces enlaces, AdminInicial adminInicial) {

    public record VidaEnlaces(Duration bienvenida, Duration recuperacion) {
    }

    public record AdminInicial(String correo, String contrasena, String nombre) {
    }
}
