package gt.cunoc.sa.identity.application;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import gt.cunoc.sa.identity.api.sucursal.SucursalRepository;
import gt.cunoc.sa.identity.api.usuario.UsuarioRepository;
import gt.cunoc.sa.identity.domain.Nit;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.messaging.EventoNoProcesableException;
import gt.cunoc.sa.identity.infrastructure.messaging.Inbox;
import gt.cunoc.sa.identity.infrastructure.messaging.Sobre;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.ChoferGuardado;
import gt.cunoc.sa.identity.infrastructure.redis.Sesiones;

/**
 * Aplica ChoferGuardado (E5): crea o actualiza la cuenta DRIVER del chofer. Un chofer sin correo
 * no tiene cuenta.
 *
 * Todo en una transacción con el inbox: un duplicado no tiene efecto, y si algo falla el inbox se
 * revierte con el resto y el reintento vuelve a empezar.
 */
@Service
public class CuentasDeChofer {

    private static final Logger log = LoggerFactory.getLogger(CuentasDeChofer.class);

    private final Inbox inbox;
    private final UsuarioRepository usuarios;
    private final SucursalRepository sucursales;
    private final AltaDeCuentas altas;
    private final Sesiones sesiones;
    private final ObjectMapper json;

    public CuentasDeChofer(Inbox inbox, UsuarioRepository usuarios, SucursalRepository sucursales,
            AltaDeCuentas altas, Sesiones sesiones, ObjectMapper json) {
        this.inbox = inbox;
        this.usuarios = usuarios;
        this.sucursales = sucursales;
        this.altas = altas;
        this.sesiones = sesiones;
        this.json = json;
    }

    @Transactional
    public void aplicar(Sobre sobre) {
        if (!inbox.primeraVez(sobre.eventId())) {
            log.debug("ChoferGuardado {} duplicado; se descarta", sobre.eventId());
            return;
        }
        ChoferGuardado chofer = leer(sobre);
        long version = sobre.aggregateVersion();
        SucursalEntity sucursal = sucursales.findById(chofer.sucursalId())
                .orElseThrow(() -> new EventoNoProcesableException("La sucursal " + chofer.sucursalId()
                        + " del chofer no existe en identity"));
        String correo = chofer.correo() == null || chofer.correo().isBlank()
                ? null : UsuarioEntity.normalizarCorreo(chofer.correo());

        Optional<UsuarioEntity> existente = usuarios.findByChoferId(chofer.choferId());
        if (existente.isEmpty()) {
            // Sin correo no hay a dónde mandar el enlace; inactivo no necesita cuenta. Si
            // después se le agrega correo o se reactiva, el siguiente E5 la crea.
            if (correo == null || !chofer.activo()) {
                return;
            }
            exigirCorreoLibre(correo);
            UsuarioEntity cuenta = UsuarioEntity.pendienteDeChofer(chofer.choferId(), version, correo,
                    chofer.nombreCompleto(), sucursal, altas.hashInutilizable(), Instant.now());
            altas.registrar(cuenta, Nit.CONSUMIDOR_FINAL, null, chofer.telefono(), null);
            return;
        }

        UsuarioEntity cuenta = existente.get();
        // Dos ediciones del chofer que llegan al revés: la vieja no pisa a la nueva.
        if (!cuenta.aceptaVersionDeChofer(version)) {
            log.info("ChoferGuardado v{} del chofer {} ya superado (v{}); se descarta", version,
                    chofer.choferId(), cuenta.getChoferVersion());
            return;
        }
        if (correo != null && !correo.equals(cuenta.getCorreo())) {
            exigirCorreoLibre(correo);
        }
        boolean cerrarSesiones = cuenta.aplicarChofer(version, correo, chofer.nombreCompleto(), sucursal,
                chofer.activo(), Instant.now());
        usuarios.flush();
        if (cerrarSesiones) {
            sesiones.cortar(cuenta.getId());
        }
    }

    /** Ningún reintento lo va a arreglar: va a la DLQ hasta que fleet corrija el correo. */
    private void exigirCorreoLibre(String correo) {
        if (usuarios.existsByCorreo(correo)) {
            throw new EventoNoProcesableException("El correo del chofer ya pertenece a otra cuenta");
        }
    }

    private ChoferGuardado leer(Sobre sobre) {
        ChoferGuardado chofer;
        try {
            chofer = json.treeToValue(sobre.payload(), ChoferGuardado.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new EventoNoProcesableException("Payload de ChoferGuardado ilegible: " + e.getMessage(), e);
        }
        if (chofer.choferId() == null || chofer.sucursalId() == null || chofer.nombreCompleto() == null) {
            throw new EventoNoProcesableException("ChoferGuardado sin choferId, sucursalId o nombreCompleto");
        }
        // Se revisa aquí y no se deja a la base: un id vacío sí entraría en la columna (y sería el
        // chofer de todos los E5 vacíos), y uno largo daría un error de SQL que se reintentaría
        // cuatro veces antes de llegar a la DLQ, que es adonde va de todos modos.
        if (chofer.choferId().isBlank() || chofer.choferId().length() > UsuarioEntity.LARGO_MAXIMO_CHOFER_ID) {
            throw new EventoNoProcesableException("ChoferGuardado con un choferId vacío o de más de "
                    + UsuarioEntity.LARGO_MAXIMO_CHOFER_ID + " caracteres");
        }
        return chofer;
    }
}
