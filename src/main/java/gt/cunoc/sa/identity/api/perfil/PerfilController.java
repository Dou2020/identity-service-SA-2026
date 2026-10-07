package gt.cunoc.sa.identity.api.perfil;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gt.cunoc.sa.identity.api.perfil.dto.ActualizarPerfilRequest;
import gt.cunoc.sa.identity.api.perfil.dto.PerfilResponse;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/perfil")
@Tag(name = "Perfil", description = "Cuenta y perfil del usuario que inició sesión")
public class PerfilController {

    private final PerfilService servicio;

    public PerfilController(PerfilService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    @Operation(summary = "Cuenta y perfil propios", description = "completo = false si falta DPI, teléfono o dirección")
    public PerfilResponse obtener(@AuthenticationPrincipal UsuarioActual actual) {
        return servicio.obtener(actual);
    }

    @PutMapping
    @Operation(summary = "Guarda el perfil propio",
            description = "409 si el NIT o el DPI ya son de otra cuenta; 422 si el NIT es inválido")
    public PerfilResponse actualizar(@RequestBody @Valid ActualizarPerfilRequest solicitud,
            @AuthenticationPrincipal UsuarioActual actual) {
        return servicio.actualizar(actual, solicitud);
    }
}
