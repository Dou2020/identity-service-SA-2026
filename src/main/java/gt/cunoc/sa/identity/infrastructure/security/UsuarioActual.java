package gt.cunoc.sa.identity.infrastructure.security;

import java.util.Set;
import java.util.UUID;

/**
 * Principal del SecurityContext, armado con los X-User-* que escribe el gateway.
 * sucursalId solo viene para BRANCH_ADMIN y CASHIER; choferId solo para DRIVER, y es texto
 * porque es el id de fleet tal cual (hoy numérico), no un id de identity.
 */
public record UsuarioActual(UUID usuarioId, UUID sucursalId, String choferId, Set<String> roles) {

    public boolean tieneRol(String rol) {
        return roles.contains(rol);
    }
}
