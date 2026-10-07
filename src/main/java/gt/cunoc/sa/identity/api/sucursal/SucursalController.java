package gt.cunoc.sa.identity.api.sucursal;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import gt.cunoc.sa.identity.api.sucursal.dto.CrearSucursalRequest;
import gt.cunoc.sa.identity.api.sucursal.dto.DatosSucursalRequest;
import gt.cunoc.sa.identity.api.sucursal.dto.EstadoSucursalRequest;
import gt.cunoc.sa.identity.api.sucursal.dto.SucursalCreadaResponse;
import gt.cunoc.sa.identity.api.sucursal.dto.SucursalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sucursales")
@Tag(name = "Sucursales", description = "Sucursales con su ubicación (latitud y longitud para el mapa de rutas)")
public class SucursalController {

    private final SucursalService servicio;

    public SucursalController(SucursalService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    @Operation(summary = "Lista ordenada por nombre; filtro opcional por estado")
    public List<SucursalResponse> listar(@RequestParam(required = false) Boolean activa) {
        return servicio.listar(activa);
    }

    @GetMapping("/{id}")
    public SucursalResponse obtener(@PathVariable UUID id) {
        return servicio.obtener(id);
    }

    @PostMapping
    @Operation(summary = "Crea la sucursal y su primer administrador en una transacción",
            description = "El administrador queda pendiente y recibe el enlace para definir su contraseña. "
                    + "409 si el código, el nombre o el correo ya existen")
    public ResponseEntity<SucursalCreadaResponse> crear(@RequestBody @Valid CrearSucursalRequest solicitud) {
        SucursalCreadaResponse creada = servicio.crear(solicitud);
        return ResponseEntity.created(URI.create("/api/sucursales/" + creada.sucursal().id())).body(creada);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Actualiza datos y ubicación")
    public SucursalResponse actualizar(@PathVariable UUID id, @RequestBody @Valid DatosSucursalRequest datos) {
        return servicio.actualizar(id, datos);
    }

    @PatchMapping("/{id}/estado")
    @Operation(summary = "Activa o desactiva la sucursal")
    public SucursalResponse cambiarEstado(@PathVariable UUID id, @RequestBody @Valid EstadoSucursalRequest solicitud) {
        return servicio.cambiarEstado(id, solicitud.activa());
    }
}
