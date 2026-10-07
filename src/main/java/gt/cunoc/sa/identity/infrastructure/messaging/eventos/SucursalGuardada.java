package gt.cunoc.sa.identity.infrastructure.messaging.eventos;

import java.math.BigDecimal;
import java.util.UUID;

/** E1: réplica de la sucursal en fleet, trip y finance. aggregateVersion = sucursal.version. */
public record SucursalGuardada(
        UUID sucursalId,
        String codigo,
        String nombre,
        String departamento,
        String municipio,
        BigDecimal latitud,
        BigDecimal longitud,
        boolean activa) {
}
