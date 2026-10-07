package gt.cunoc.sa.identity.api.tarifa.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import gt.cunoc.sa.identity.domain.TarifaEntity;
import gt.cunoc.sa.identity.domain.TipoTarifa;

public record TarifaResponse(Long id, TipoTarifa tipo, BigDecimal valor, LocalDate vigenteDesde, Instant registradoEn) {

    public static TarifaResponse de(TarifaEntity t) {
        return new TarifaResponse(t.getId(), t.getTipo(), t.getValor(), t.getVigenteDesde(), t.getRegistradoEn());
    }
}
