package gt.cunoc.sa.identity.api.perfil.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Guardar el perfil exige todos los datos: así es como una cuenta creada por un admin lo
 * completa, y ninguna lo puede dejar incompleto después.
 *
 * @param nit vacío o "CF" = consumidor final
 */
public record ActualizarPerfilRequest(
        @NotBlank @Size(max = 150) String nombreCompleto,
        @Size(max = 20) String nit,
        @NotBlank @Pattern(regexp = "\\d{13}", message = "debe tener 13 dígitos") String dpi,
        @NotBlank @Pattern(regexp = "[0-9+ -]{8,15}", message = "no es un teléfono válido") String telefono,
        @NotBlank @Size(max = 200) String direccion) {
}
