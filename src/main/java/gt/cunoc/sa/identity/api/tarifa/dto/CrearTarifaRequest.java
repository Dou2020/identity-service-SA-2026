package gt.cunoc.sa.identity.api.tarifa.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import gt.cunoc.sa.identity.domain.TipoTarifa;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Agrega una versión; nunca modifica la anterior.
 *
 * @param vigenteDesde la elige el usuario; hoy si no viene (requisito de calificación)
 */
public record CrearTarifaRequest(
        @NotNull TipoTarifa tipo,
        @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal valor,
        LocalDate vigenteDesde) {
}
