package gt.cunoc.sa.identity.infrastructure.messaging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import gt.cunoc.sa.identity.application.CuentasDeChofer;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;
import gt.cunoc.sa.identity.infrastructure.web.CorrelationIdFilter;

/**
 * Consume identity.events. Lee el sobre a mano (no con un MessageConverter) para que un cuerpo
 * ilegible sea un EventoNoProcesable que va directo a la DLQ.
 */
@Component
public class ListenerEventos {

    private static final Logger log = LoggerFactory.getLogger(ListenerEventos.class);

    private final ObjectMapper json;
    private final CuentasDeChofer cuentasDeChofer;

    public ListenerEventos(ObjectMapper json, CuentasDeChofer cuentasDeChofer) {
        this.json = json;
        this.cuentasDeChofer = cuentasDeChofer;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA)
    public void recibir(Message mensaje) {
        Sobre sobre = leer(mensaje);
        // El mismo X-Correlation-Id del request que originó el evento: el flujo se sigue en
        // los logs, y los eventos que este produzca lo heredan (Outbox lo toma del MDC).
        if (sobre.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_CLAVE, sobre.correlationId());
        }
        try {
            switch (sobre.type()) {
                case TiposEvento.CHOFER_GUARDADO -> cuentasDeChofer.aplicar(sobre);
                // La cola solo se enlaza a lo que este servicio consume; si llega otra cosa es
                // un binding de más, no un error del mensaje.
                default -> log.warn("Evento {} sin manejador en identity; se descarta", sobre.type());
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_CLAVE);
        }
    }

    private Sobre leer(Message mensaje) {
        Sobre sobre;
        try {
            sobre = json.readValue(mensaje.getBody(), Sobre.class);
        } catch (IOException e) {
            throw new EventoNoProcesableException("Sobre ilegible: " + e.getMessage(), e);
        }
        if (sobre.eventId() == null || sobre.type() == null || sobre.payload() == null) {
            throw new EventoNoProcesableException("Sobre sin eventId, type o payload");
        }
        return sobre;
    }
}
