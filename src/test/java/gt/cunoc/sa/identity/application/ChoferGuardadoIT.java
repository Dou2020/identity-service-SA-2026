package gt.cunoc.sa.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import gt.cunoc.sa.identity.Escenario;
import gt.cunoc.sa.identity.PruebaDeIntegracion;
import gt.cunoc.sa.identity.api.usuario.UsuarioRepository;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.messaging.Sobre;
import gt.cunoc.sa.identity.infrastructure.messaging.TopologiaRabbit;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;
import gt.cunoc.sa.identity.infrastructure.redis.Sesiones;

/**
 * ChoferGuardado (E5) publicado como lo haría fleet: duplicados, desorden y mensajes que nunca
 * van a funcionar (README, "Manejo de fallos").
 */
@PruebaDeIntegracion
class ChoferGuardadoIT {

    private static final Duration ESPERA = Duration.ofSeconds(10);

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    AmqpAdmin admin;
    @Autowired
    ObjectMapper json;
    @Autowired
    Escenario escenario;
    @Autowired
    UsuarioRepository usuarios;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    MockMvc mvc;

    @Test
    void elMismoEventoDosVecesTieneUnSoloEfecto() throws Exception {
        SucursalEntity sucursal = escenario.sucursal();
        UUID choferId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String correo = Escenario.correo();

        publicar(eventId, choferId, 1, chofer(sucursal, correo, "Pedro Pérez", true));
        UsuarioEntity cuenta = await().atMost(ESPERA).until(() -> usuarios.findByChoferId(choferId).orElse(null),
                u -> u != null);
        assertThat(cuenta.getRol()).isEqualTo(Rol.DRIVER);
        assertThat(cuenta.isConfirmado()).isFalse();

        // Mismo eventId (un reintento del relay) aunque traiga otro contenido: el inbox lo descarta.
        publicar(eventId, choferId, 2, chofer(sucursal, correo, "Otro nombre", true));
        await().atMost(ESPERA).until(() -> procesados(eventId) == 1);
        Thread.sleep(500);
        assertThat(usuarios.findByChoferId(choferId).orElseThrow().getNombreCompleto()).isEqualTo("Pedro Pérez");
    }

    @Test
    void unEventoViejoNoPisaAUnoNuevoYDesactivarCierraLasSesiones() throws Exception {
        SucursalEntity sucursal = escenario.sucursal();
        UUID choferId = UUID.randomUUID();
        String correo = Escenario.correo();

        publicar(UUID.randomUUID(), choferId, 2, chofer(sucursal, correo, "Versión 2", true));
        await().atMost(ESPERA).until(() -> usuarios.findByChoferId(choferId).isPresent());
        UUID viejo = UUID.randomUUID();
        publicar(viejo, choferId, 1, chofer(sucursal, correo, "Versión 1", true));
        await().atMost(ESPERA).until(() -> procesados(viejo) == 1);
        assertThat(usuarios.findByChoferId(choferId).orElseThrow().getNombreCompleto()).isEqualTo("Versión 2");

        publicar(UUID.randomUUID(), choferId, 3, chofer(sucursal, correo, "Versión 2", false));
        UsuarioEntity cuenta = await().atMost(ESPERA).until(() -> usuarios.findByChoferId(choferId).orElseThrow(),
                u -> !u.isActivo());
        assertThat(redis.hasKey(Sesiones.VALIDO_DESDE + cuenta.getId())).isTrue();
    }

    @Test
    void unChoferSinCorreoNoTieneCuenta() throws Exception {
        UUID choferId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        publicar(eventId, choferId, 1, chofer(escenario.sucursal(), null, "Sin correo", true));

        await().atMost(ESPERA).until(() -> procesados(eventId) == 1);
        assertThat(usuarios.findByChoferId(choferId)).isEmpty();
    }

    /** Ningún reintento arregla un correo tomado: va a la DLQ sin gastar los reintentos. */
    @Test
    void unCorreoDeOtraCuentaVaALaDlqYSePuedeReprocesar() throws Exception {
        String tomado = escenario.usuario(Rol.CUSTOMER, null).getCorreo();
        int antes = mensajesEnDlq();

        publicar(UUID.randomUUID(), UUID.randomUUID(), 1, chofer(escenario.sucursal(), tomado, "Duplicado", true));

        await().atMost(Duration.ofSeconds(5)).until(() -> mensajesEnDlq() == antes + 1);
        mvc.perform(post("/internal/dlq/reprocesar")).andExpect(status().isOk());
        // Vuelve a fallar y regresa a la DLQ: el reprocesamiento no lo pierde.
        await().atMost(Duration.ofSeconds(5)).until(() -> mensajesEnDlq() == antes + 1);
    }

    private Map<String, Object> chofer(SucursalEntity sucursal, String correo, String nombre, boolean activo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sucursalId", sucursal.getId());
        payload.put("nombreCompleto", nombre);
        payload.put("correo", correo);
        payload.put("telefono", "55550000");
        payload.put("activo", activo);
        payload.put("numeroLicencia", "A-123");   // campos de fleet que identity ignora
        return payload;
    }

    private void publicar(UUID eventId, UUID choferId, long version, Map<String, Object> payload) throws Exception {
        payload.put("choferId", choferId);
        Sobre sobre = new Sobre(eventId, TiposEvento.CHOFER_GUARDADO, 1, choferId.toString(), version, Instant.now(),
                "fleet-service", "prueba-" + eventId, json.valueToTree(payload));
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(TopologiaRabbit.EXCHANGE_EVENTOS, TiposEvento.CHOFER_GUARDADO,
                new Message(json.writeValueAsBytes(sobre), propiedades));
    }

    private int procesados(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_event where event_id = ?", Integer.class, eventId);
    }

    private int mensajesEnDlq() {
        return ((Number) admin.getQueueProperties(TopologiaRabbit.COLA_DLQ).get(RabbitAdmin.QUEUE_MESSAGE_COUNT))
                .intValue();
    }
}
