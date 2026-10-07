package gt.cunoc.sa.identity.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RecuperacionRequest(@NotBlank @Email String correo) {
}
