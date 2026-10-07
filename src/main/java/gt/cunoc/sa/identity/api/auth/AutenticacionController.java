package gt.cunoc.sa.identity.api.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gt.cunoc.sa.identity.api.auth.dto.ContrasenaRequest;
import gt.cunoc.sa.identity.api.auth.dto.LoginRequest;
import gt.cunoc.sa.identity.api.auth.dto.LoginResponse;
import gt.cunoc.sa.identity.api.auth.dto.MensajeResponse;
import gt.cunoc.sa.identity.api.auth.dto.RecuperacionRequest;
import gt.cunoc.sa.identity.api.auth.dto.RegistroRequest;
import gt.cunoc.sa.identity.api.auth.dto.RegistroResponse;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticación", description = "Login, registro, contraseña por enlace y cierre de sesión")
public class AutenticacionController {

    private final AutenticacionService servicio;

    public AutenticacionController(AutenticacionService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Inicia sesión y devuelve un JWT de 8 h",
            description = "401 si el correo o la contraseña no coinciden, o si la cuenta está inactiva o pendiente")
    public LoginResponse login(@RequestBody @Valid LoginRequest solicitud) {
        return servicio.iniciarSesion(solicitud.correo(), solicitud.contrasena());
    }

    @PostMapping("/registro")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Autorregistro de cliente, sin contraseña",
            description = "La cuenta queda pendiente y recibe por correo un enlace de 72 h para definir la "
                    + "contraseña. 409 si el correo, el NIT o el DPI ya son de otra cuenta; 422 si el NIT es inválido")
    public RegistroResponse registrar(@RequestBody @Valid RegistroRequest solicitud) {
        UsuarioEntity usuario = servicio.registrar(solicitud);
        return new RegistroResponse(usuario.getId(),
                "Te enviamos un correo con el enlace para definir tu contraseña");
    }

    @PostMapping("/recuperacion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(summary = "Pide un enlace para recuperar la contraseña",
            description = "Responde siempre 202, exista o no la cuenta, para no revelar qué correos están registrados")
    public MensajeResponse recuperar(@RequestBody @Valid RecuperacionRequest solicitud) {
        servicio.solicitarRecuperacion(solicitud.correo());
        return new MensajeResponse("Si el correo está registrado, recibirás un enlace para definir tu contraseña");
    }

    @PostMapping("/contrasena")
    @SecurityRequirements
    @Operation(summary = "Define la contraseña con el token del enlace (bienvenida o recuperación)",
            description = "El primer uso confirma la cuenta. Cierra todas las sesiones abiertas. "
                    + "400 si el enlace no es válido, ya se usó o venció")
    public MensajeResponse definirContrasena(@RequestBody @Valid ContrasenaRequest solicitud) {
        servicio.definirContrasena(solicitud.token(), solicitud.nueva());
        return new MensajeResponse("Tu contraseña quedó guardada. Ya puedes iniciar sesión");
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cierra la sesión: revoca el token con el que se hace la request")
    public void logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @AuthenticationPrincipal UsuarioActual actual) {
        servicio.cerrarSesion(authorization, actual);
    }
}
