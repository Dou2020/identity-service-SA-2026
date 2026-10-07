package gt.cunoc.sa.identity.api.tarifa;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gt.cunoc.sa.identity.api.tarifa.dto.CrearTarifaRequest;
import gt.cunoc.sa.identity.api.tarifa.dto.TarifaResponse;
import gt.cunoc.sa.identity.domain.TipoTarifa;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/tarifas")
@Tag(name = "Tarifas", description = "Depreciación por km y tarifas de alquiler, versionadas por fecha de vigencia")
public class TarifaController {

    private final TarifaService servicio;

    public TarifaController(TarifaService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/vigentes")
    @Operation(summary = "La versión vigente de cada tipo a la fecha (hoy por defecto)")
    public List<TarifaResponse> vigentes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        return servicio.vigentes(fecha);
    }

    @GetMapping
    @Operation(summary = "Historial de versiones, la más reciente primero")
    public List<TarifaResponse> historial(@RequestParam(required = false) TipoTarifa tipo) {
        return servicio.historial(tipo);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Agrega una versión; no modifica las anteriores",
            description = "vigenteDesde es hoy si no se indica. 409 si ya hay una de ese tipo con esa fecha")
    public TarifaResponse crear(@RequestBody @Valid CrearTarifaRequest solicitud,
            @AuthenticationPrincipal UsuarioActual actual) {
        return servicio.crear(solicitud, actual);
    }
}
