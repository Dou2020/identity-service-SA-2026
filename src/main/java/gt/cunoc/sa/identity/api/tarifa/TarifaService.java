package gt.cunoc.sa.identity.api.tarifa;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gt.cunoc.sa.identity.api.tarifa.dto.CrearTarifaRequest;
import gt.cunoc.sa.identity.api.tarifa.dto.TarifaResponse;
import gt.cunoc.sa.identity.domain.Calendario;
import gt.cunoc.sa.identity.domain.TarifaEntity;
import gt.cunoc.sa.identity.domain.TipoTarifa;
import gt.cunoc.sa.identity.infrastructure.messaging.Outbox;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.ConfiguracionActualizada;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;

/**
 * Tarifas versionadas: depreciación por km (finance) y tarifas de alquiler (customer).
 * Cambiar una tarifa agrega una versión con su vigente_desde; nunca toca la anterior, así que
 * lo ya calculado con ella no cambia.
 */
@Service
public class TarifaService {

    private final TarifaRepository tarifas;
    private final Outbox outbox;

    public TarifaService(TarifaRepository tarifas, Outbox outbox) {
        this.tarifas = tarifas;
        this.outbox = outbox;
    }

    /** La versión vigente de cada tipo a la fecha (hoy si no se indica); los tipos sin versión no aparecen. */
    @PreAuthorize("hasAnyRole('SYS_ADMIN', 'BRANCH_ADMIN')")
    @Transactional(readOnly = true)
    public List<TarifaResponse> vigentes(LocalDate fecha) {
        LocalDate dia = fecha == null ? Calendario.hoy() : fecha;
        return Arrays.stream(TipoTarifa.values())
                .map(tipo -> tarifas.findFirstByTipoAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(tipo, dia))
                .flatMap(Optional::stream)
                .map(TarifaResponse::de)
                .toList();
    }

    @PreAuthorize("hasRole('SYS_ADMIN')")
    @Transactional(readOnly = true)
    public List<TarifaResponse> historial(TipoTarifa tipo) {
        List<TarifaEntity> lista = tipo == null
                ? tarifas.findAllByOrderByTipoAscVigenteDesdeDesc()
                : tarifas.findByTipoOrderByVigenteDesdeDesc(tipo);
        return lista.stream().map(TarifaResponse::de).toList();
    }

    /** Dos versiones del mismo tipo con la misma fecha → 409 (tarifa_tipo_fecha_uq). */
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @Transactional
    public TarifaResponse crear(CrearTarifaRequest solicitud, UsuarioActual actual) {
        LocalDate vigenteDesde = solicitud.vigenteDesde() == null ? Calendario.hoy() : solicitud.vigenteDesde();
        TarifaEntity tarifa = tarifas.saveAndFlush(new TarifaEntity(solicitud.tipo(), solicitud.valor(), vigenteDesde,
                actual.usuarioId(), Instant.now()));
        // aggregateVersion = id: crece con cada versión. Los consumidores guardan todas las
        // versiones (por id), no solo la última.
        outbox.registrar(TiposEvento.CONFIGURACION_ACTUALIZADA, tarifa.getTipo().name(), tarifa.getId(),
                new ConfiguracionActualizada(tarifa.getId(), tarifa.getTipo(), tarifa.getValor(),
                        tarifa.getVigenteDesde()));
        return TarifaResponse.de(tarifa);
    }
}
