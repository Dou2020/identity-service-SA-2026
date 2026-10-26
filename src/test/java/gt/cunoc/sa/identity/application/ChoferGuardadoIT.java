package gt.cunoc.sa.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import gt.cunoc.sa.identity.ColaDePrueba;
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
 * van a funcionar (README, "Manejo de fallos"). El choferId es texto: fleet manda su id numérico
 * ("3"), y los casos con UUID en texto siguen valiendo.
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
        String choferId = UUID.randomUUID().toString();
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

    /** El orden lo decide aggregateVersion, sea cual sea la forma del id. */
    @ParameterizedTest(name = "choferId {0}")
    @MethodSource("idsDeChofer")
    void unEventoViejoNoPisaAUnoNuevoYDesactivarCierraLasSesiones(String choferId) throws Exception {
        SucursalEntity sucursal = escenario.sucursal();
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

    /**
     * El caso de fleet de punta a punta: su id numérico crea la cuenta, el chofer la activa con el
     * enlace de E3, su JWT lleva choferId "3" como string, y con el X-Chofer-Id que el gateway saca
     * de ese claim identity lo sigue reconociendo.
     */
    @Test
    void unChoferConElIdNumericoDeFleetTieneCuentaYSuJwtLlevaEseId() throws Exception {
        SucursalEntity sucursal = escenario.sucursal();
        String correo = Escenario.correo();
        try (ColaDePrueba eventos = new ColaDePrueba(admin, rabbit, json, TiposEvento.USUARIO_CREADO)) {
            publicar(UUID.randomUUID(), "3", 1, chofer(sucursal, correo, "Chofer Tres", true));

            UsuarioEntity cuenta = await().atMost(ESPERA).until(() -> usuarios.findByChoferId("3").orElse(null),
                    u -> u != null);
            assertThat(cuenta.getRol()).isEqualTo(Rol.DRIVER);
            assertThat(cuenta.getChoferId()).isEqualTo("3");
            assertThat(cuenta.isConfirmado()).isFalse();

            String enlace = eventos.esperar(TiposEvento.USUARIO_CREADO,
                    s -> s.aggregateId().equals(cuenta.getId().toString())).payload().get("token").asText();
            enviar("/api/auth/contrasena", Map.of("token", enlace, "nueva", "Chofer1234")).andExpect(status().isOk());

            String respuesta = enviar("/api/auth/login", Map.of("correo", correo, "contrasena", "Chofer1234"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usuario.choferId").value("3"))
                    .andReturn().getResponse().getContentAsString();
            JWTClaimsSet claims = SignedJWT.parse(json.readTree(respuesta).get("token").asText()).getJWTClaimsSet();
            assertThat(claims.getStringClaim("choferId")).isEqualTo("3");
            assertThat(claims.getStringListClaim("roles")).containsExactly("DRIVER");

            mvc.perform(get("/api/perfil")
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Roles", "DRIVER")
                    .header("X-Chofer-Id", claims.getStringClaim("choferId")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usuarioId").value(cuenta.getId().toString()))
                    .andExpect(jsonPath("$.choferId").value("3"));
        }
    }

    @Test
    void unChoferSinCorreoNoTieneCuenta() throws Exception {
        String choferId = UUID.randomUUID().toString();
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

        publicar(UUID.randomUUID(), UUID.randomUUID().toString(), 1,
                chofer(escenario.sucursal(), tomado, "Duplicado", true));

        await().atMost(Duration.ofSeconds(5)).until(() -> mensajesEnDlq() == antes + 1);
        mvc.perform(post("/internal/dlq/reprocesar")).andExpect(status().isOk());
        // Vuelve a fallar y regresa a la DLQ: el reprocesamiento no lo pierde.
        await().atMost(Duration.ofSeconds(5)).until(() -> mensajesEnDlq() == antes + 1);
    }

    /**
     * Un id vacío o que no cabe en la columna tampoco se arregla reintentando: directo a la DLQ (en
     * menos de los 15 s que tardarían los reintentos) y sin cuenta.
     */
    @ParameterizedTest(name = "choferId «{0}»")
    @MethodSource("idsInvalidos")
    void unChoferIdVacioODemasiadoLargoVaDirectoALaDlq(String choferId) throws Exception {
        UUID eventId = UUID.randomUUID();
        publicar(eventId, choferId, 1, chofer(escenario.sucursal(), Escenario.correo(), "Id inválido", true));

        await().atMost(Duration.ofSeconds(5)).until(() -> llegoALaDlq(eventId));
        assertThat(usuarios.findByChoferId(choferId)).isEmpty();
        assertThat(procesados(eventId)).isZero();
    }

    /** Un UUID en texto (como los fixtures de antes) y un id numérico como los de fleet. */
    static Stream<String> idsDeChofer() {
        return Stream.of(UUID.randomUUID().toString(),
                Long.toString(ThreadLocalRandom.current().nextLong(1_000, 1_000_000_000)));
    }

    static Stream<String> idsInvalidos() {
        return Stream.of("", "   ", "9".repeat(UsuarioEntity.LARGO_MAXIMO_CHOFER_ID + 1));
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

    /** Como fleet: choferId en el payload y en aggregateId, siempre como texto. */
    private void publicar(UUID eventId, String choferId, long version, Map<String, Object> payload) throws Exception {
        payload.put("choferId", choferId);
        Sobre sobre = new Sobre(eventId, TiposEvento.CHOFER_GUARDADO, 1, choferId, version, Instant.now(),
                "fleet-service", "prueba-" + eventId, json.valueToTree(payload));
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(TopologiaRabbit.EXCHANGE_EVENTOS, TiposEvento.CHOFER_GUARDADO,
                new Message(json.writeValueAsBytes(sobre), propiedades));
    }

    private ResultActions enviar(String ruta, Map<String, Object> cuerpo) throws Exception {
        return mvc.perform(post(ruta).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cuerpo)));
    }

    private int procesados(UUID eventId) {
        return jdbc.queryForObject("select count(*) from processed_event where event_id = ?", Integer.class, eventId);
    }

    private int mensajesEnDlq() {
        return ((Number) admin.getQueueProperties(TopologiaRabbit.COLA_DLQ).get(RabbitAdmin.QUEUE_MESSAGE_COUNT))
                .intValue();
    }

    /**
     * Busca el evento en la DLQ sacando lo que haya: más preciso que contar, porque un mensaje de otra
     * prueba que llegue en la misma ventana no lo confunde. Lo que saca es de pruebas ya terminadas
     * (corren una tras otra), y unCorreoDeOtraCuentaVaALaDlq cuenta desde su propio inicio.
     */
    private boolean llegoALaDlq(UUID eventId) throws Exception {
        Message mensaje = rabbit.receive(TopologiaRabbit.COLA_DLQ);
        while (mensaje != null) {
            if (eventId.equals(json.readValue(mensaje.getBody(), Sobre.class).eventId())) {
                return true;
            }
            mensaje = rabbit.receive(TopologiaRabbit.COLA_DLQ);
        }
        return false;
    }
}
