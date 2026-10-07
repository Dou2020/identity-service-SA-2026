package gt.cunoc.sa.identity.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Define la contraseña con el token del enlace (bienvenida o recuperación). La política se
 * valida aquí, antes de consumir el token: una contraseña rechazada no gasta el enlace.
 * Máximo 72: BCrypt ignora lo que pase de 72 bytes.
 */
public record ContrasenaRequest(
        @NotBlank String token,
        @NotBlank
        @Size(min = 8, max = 72)
        @Pattern(regexp = "^(?=.*\\p{L})(?=.*\\d).*$", message = "debe tener al menos una letra y un número")
        String nueva) {
}
