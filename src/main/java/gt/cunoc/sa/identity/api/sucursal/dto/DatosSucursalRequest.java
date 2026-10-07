package gt.cunoc.sa.identity.api.sucursal.dto;

import java.math.BigDecimal;

import gt.cunoc.sa.identity.domain.SucursalEntity.DatosSucursal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Latitud y longitud con 6 decimales (numeric(9,6)): las usa el mapa de rutas. */
public record DatosSucursalRequest(
        @NotBlank @Size(max = 10) @Pattern(regexp = "[A-Za-z0-9-]+", message = "solo letras, números y guiones")
        String codigo,
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Size(max = 50) String departamento,
        @NotBlank @Size(max = 50) String municipio,
        @NotBlank @Size(max = 200) String direccion,
        @Pattern(regexp = "[0-9+ -]{8,15}", message = "no es un teléfono válido") String telefono,
        @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 6) BigDecimal latitud,
        @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal longitud) {

    public DatosSucursal aDominio() {
        return new DatosSucursal(codigo, nombre, departamento, municipio, direccion, telefono, latitud, longitud);
    }
}
