package gt.cunoc.sa.identity.api.usuario.dto;

import java.util.UUID;

import gt.cunoc.sa.identity.domain.Rol;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuenta de personal creada por un admin. Nace pendiente: el empleado define su contraseña con
 * el enlace del correo y completa su perfil después (DPI, teléfono y dirección son opcionales).
 *
 * @param sucursalId obligatoria para BRANCH_ADMIN; para CASHIER se ignora (es la del admin que lo crea)
 */
public record CrearUsuarioRequest(
        @NotBlank @Email @Size(max = 150) String correo,
        @NotBlank @Size(max = 150) String nombreCompleto,
        @NotNull Rol rol,
        UUID sucursalId,
        @Size(max = 20) String nit,
        @Pattern(regexp = "\\d{13}", message = "debe tener 13 dígitos") String dpi,
        @Pattern(regexp = "[0-9+ -]{8,15}", message = "no es un teléfono válido") String telefono,
        @Size(max = 200) String direccion) {
}
