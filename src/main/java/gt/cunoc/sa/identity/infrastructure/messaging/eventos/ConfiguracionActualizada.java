package gt.cunoc.sa.identity.infrastructure.messaging.eventos;

import java.math.BigDecimal;
import java.time.LocalDate;

import gt.cunoc.sa.identity.domain.TipoTarifa;

/**
 * E2: una versión nueva de tarifa (finance y customer). aggregateId = tipo y
 * aggregateVersion = tarifaId, que crece con cada versión.
 */
public record ConfiguracionActualizada(
        long tarifaId,
        TipoTarifa tipo,
        BigDecimal valor,
        LocalDate vigenteDesde) {
}
