package gt.cunoc.sa.identity.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Autorregistro de un CUSTOMER, sin contraseña: la define con el enlace que le llega por
 * correo. A diferencia de las cuentas que crea un admin, exige el perfil completo.
 *
 * @param nit vacío o "CF" = consumidor final
 */
public record RegistroRequest(
        @NotBlank @Email @Size(max = 150) String correo,
        @NotBlank @Size(max = 150) String nombreCompleto,
        @Size(max = 20) String nit,
        @NotBlank @Pattern(regexp = "\\d{13}", message = "debe tener 13 dígitos") String dpi,
        @NotBlank @Pattern(regexp = "[0-9+ -]{8,15}", message = "no es un teléfono válido") String telefono,
        @NotBlank @Size(max = 200) String direccion) {
}
