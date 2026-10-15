package gt.cunoc.sa.identity.api.usuario;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gt.cunoc.sa.identity.api.usuario.dto.CambiarUsuarioRequest;
import gt.cunoc.sa.identity.api.usuario.dto.CrearUsuarioRequest;
import gt.cunoc.sa.identity.api.usuario.dto.EstadoUsuarioRequest;
import gt.cunoc.sa.identity.api.usuario.dto.UsuarioResponse;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/usuarios")
@Tag(name = "Usuarios", description = "Administración de usuarios: no se eliminan, solo se desactivan")
public class UsuarioController {

    private final UsuarioService servicio;

    public UsuarioController(UsuarioService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    @Operation(summary = "Lista paginada", description = "Un BRANCH_ADMIN solo ve los de su sucursal")
    public Page<UsuarioResponse> listar(@RequestParam(required = false) Rol rol,
            @RequestParam(required = false) UUID sucursalId, @RequestParam(required = false) Boolean activo,
            @PageableDefault(size = 20, sort = "nombreCompleto") Pageable pagina,
            @AuthenticationPrincipal UsuarioActual actual) {
        return servicio.listar(rol, sucursalId, activo, pagina, actual);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle con perfil")
    public UsuarioResponse obtener(@PathVariable UUID id, @AuthenticationPrincipal UsuarioActual actual) {
        return servicio.obtener(id, actual);
    }

    @PostMapping
    @Operation(summary = "Crea una cuenta de personal (pendiente, con enlace de 72 h)",
            description = "SYS_ADMIN crea SYS_ADMIN o BRANCH_ADMIN; BRANCH_ADMIN crea CASHIER de su sucursal")
    public ResponseEntity<UsuarioResponse> crear(@RequestBody @Valid CrearUsuarioRequest solicitud,
            @AuthenticationPrincipal UsuarioActual actual) {
        UsuarioResponse creado = servicio.crear(solicitud, actual);
        return ResponseEntity.created(URI.create("/api/usuarios/" + creado.id())).body(creado);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Cambia el rol o la sucursal (solo entre roles de personal) y cierra sus sesiones",
            description = "409 si deja el sistema sin SYS_ADMIN activo")
    public UsuarioResponse cambiar(@PathVariable UUID id, @RequestBody @Valid CambiarUsuarioRequest solicitud) {
        return servicio.cambiarRol(id, solicitud);
    }

    @PatchMapping("/{id}/estado")
    @Operation(summary = "Activa o desactiva; desactivar cierra sus sesiones",
            description = "BRANCH_ADMIN solo sus cajeros. Mismos 409 que el cambio de rol")
    public UsuarioResponse cambiarEstado(@PathVariable UUID id, @RequestBody @Valid EstadoUsuarioRequest solicitud,
            @AuthenticationPrincipal UsuarioActual actual) {
        return servicio.cambiarEstado(id, solicitud.activo(), actual);
    }
}
