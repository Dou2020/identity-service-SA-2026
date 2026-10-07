package gt.cunoc.sa.identity.api.sucursal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Una sucursal se da de alta junto con su primer administrador, en la misma transacción: así
 * nunca existe una sucursal sin administrador (README, "Reglas de negocio").
 */
public record CrearSucursalRequest(
        @NotNull @Valid DatosSucursalRequest sucursal,
        @NotNull @Valid NuevoAdministrador administrador) {

    public record NuevoAdministrador(
            @NotBlank @Email @Size(max = 150) String correo,
            @NotBlank @Size(max = 150) String nombreCompleto,
            @Pattern(regexp = "[0-9+ -]{8,15}", message = "no es un teléfono válido") String telefono) {
    }
}
