package gt.cunoc.sa.identity.api.interno;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import gt.cunoc.sa.identity.api.interno.dto.AdministradorResponse;
import gt.cunoc.sa.identity.infrastructure.messaging.ReprocesadorDlq;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Rutas para otros servicios y para el operador. El gateway no las expone: solo se llega por la
 * red interna de Compose (README, "Premisa: solo el gateway llega a los servicios").
 */
@RestController
@RequestMapping("/internal")
@SecurityRequirements
@Tag(name = "Interno", description = "Solo red interna; el gateway no expone /internal/**")
public class InternoController {

    private final DirectorioService directorio;
    private final ReprocesadorDlq reprocesador;

    public InternoController(DirectorioService directorio, ReprocesadorDlq reprocesador) {
        this.directorio = directorio;
        this.reprocesador = reprocesador;
    }

    @GetMapping("/sucursales/{id}/administradores")
    @Operation(summary = "S7: administradores activos de la sucursal (notification-service)")
    public List<AdministradorResponse> administradores(@PathVariable UUID id) {
        return directorio.administradoresDe(id);
    }

    @PostMapping("/dlq/reprocesar")
    @Operation(summary = "Reencola los mensajes de identity.events.dlq")
    public Map<String, Integer> reprocesarDlq() {
        return Map.of("reprocesados", reprocesador.reprocesar());
    }
}
