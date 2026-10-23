package gt.cunoc.sa.identity.application;

import static gt.cunoc.sa.identity.Escenario.como;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;

import gt.cunoc.sa.identity.ColaDePrueba;
import gt.cunoc.sa.identity.Escenario;
import gt.cunoc.sa.identity.PruebaDeIntegracion;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.messaging.Sobre;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;
import gt.cunoc.sa.identity.infrastructure.redis.Enlaces;
import gt.cunoc.sa.identity.infrastructure.redis.Sesiones;

/** README, "Pruebas" → "Registro y recuperación de contraseña". */
@PruebaDeIntegracion
class AutenticacionIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Escenario escenario;
    @Autowired
    ObjectMapper json;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AmqpAdmin admin;
    @Autowired
    RabbitTemplate rabbit;

    ColaDePrueba eventos;

    @BeforeEach
    void escucharEventos() {
        eventos = new ColaDePrueba(admin, rabbit, json, "identity.#");
    }

    @AfterEach
    void cerrar() {
        eventos.close();
    }

    @Test
    void elRegistroEmiteUsuarioCreadoYElPrimerUsoDelEnlaceConfirmaYEmitePerfilGuardado() throws Exception {
        String correo = Escenario.correo();
        String nit = Escenario.nit();
        UUID id = UUID.fromString(leer(publicar("/api/auth/registro", Map.of("correo", correo,
                "nombreCompleto", "Ana López", "nit", nit, "dpi", Escenario.dpi(), "telefono", "5555-1234",
                "direccion", "Zona 3, Quetzaltenango"))
                .andExpect(status().isCreated())).get("usuarioId").asText());

        Sobre creado = eventos.esperar(TiposEvento.USUARIO_CREADO, s -> s.aggregateId().equals(id.toString()));
        assertThat(creado.payload().get("rol").asText()).isEqualTo("CUSTOMER");
        String token = creado.payload().get("token").asText();
        // Pendiente: no puede iniciar sesión y customer todavía no sabe que existe.
        publicar("/api/auth/login", Map.of("correo", correo, "contrasena", "Secreta123"))
                .andExpect(status().isUnauthorized());
        assertThat(eventos.durante(Duration.ofMillis(800)))
                .noneMatch(s -> s.type().equals(TiposEvento.PERFIL_GUARDADO) && s.aggregateId().equals(id.toString()));

        publicar("/api/auth/contrasena", Map.of("token", token, "nueva", "Secreta123")).andExpect(status().isOk());

        Sobre perfil = eventos.esperar(TiposEvento.PERFIL_GUARDADO, s -> s.aggregateId().equals(id.toString()));
        assertThat(perfil.aggregateVersion()).isEqualTo(1);
        assertThat(perfil.payload().get("nit").asText()).isEqualTo(nit);
        assertThat(perfil.payload().get("correo").asText()).isEqualTo(correo);
        publicar("/api/auth/login", Map.of("correo", correo.toUpperCase(), "contrasena", "Secreta123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.rol").value("CUSTOMER"));
        // Un solo uso.
        publicar("/api/auth/contrasena", Map.of("token", token, "nueva", "OtraClave99"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void elRelayVaciaElPayloadDeLosEventosQueLlevanElToken() throws Exception {
        UUID id = UUID.fromString(leer(publicar("/api/auth/registro", Map.of("correo", Escenario.correo(),
                "nombreCompleto", "Luis", "dpi", Escenario.dpi(), "telefono", "55551234", "direccion", "Zona 1"))
                .andExpect(status().isCreated())).get("usuarioId").asText());

        eventos.esperar(TiposEvento.USUARIO_CREADO, s -> s.aggregateId().equals(id.toString()));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                select payload::text from outbox_event
                where tipo = ? and aggregate_id = ? and publicado_en is not null""",
                String.class, TiposEvento.USUARIO_CREADO, id.toString())).isEqualTo("{}"));
    }

    @Test
    void unEnlaceVencidoOInventadoNoSirveYNoGastaLaValidacion() throws Exception {
        UsuarioEntity usuario = escenario.pendiente(Rol.CUSTOMER, null);
        String token = enlaceDeRecuperacion(usuario);

        // La contraseña se valida antes de consumir el token: una débil no gasta el enlace.
        publicar("/api/auth/contrasena", Map.of("token", token, "nueva", "corta")).andExpect(status().isBadRequest());
        assertThat(redis.hasKey(Enlaces.RESTABLECER + Enlaces.sha256(token))).isTrue();

        redis.delete(Enlaces.RESTABLECER + Enlaces.sha256(token));   // lo que hace el TTL al vencer
        publicar("/api/auth/contrasena", Map.of("token", token, "nueva", "Secreta123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("El enlace no es válido o ya venció. Solicita uno nuevo"));
        publicar("/api/auth/contrasena", Map.of("token", "x".repeat(43), "nueva", "Secreta123"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void definirLaContrasenaCierraLasSesionesAbiertas() throws Exception {
        UsuarioEntity usuario = escenario.usuario(Rol.CASHIER, escenario.sucursal());
        String jwt = leer(publicar("/api/auth/login", Map.of("correo", usuario.getCorreo(),
                "contrasena", Escenario.CONTRASENA)).andExpect(status().isOk())).get("token").asText();
        long iat = SignedJWT.parse(jwt).getJWTClaimsSet().getIssueTime().toInstant().getEpochSecond();
        // El corte tiene precisión de segundos: un token del mismo segundo sigue valiendo.
        await().until(() -> Instant.now().getEpochSecond() > iat);

        publicar("/api/auth/contrasena", Map.of("token", enlaceDeRecuperacion(usuario), "nueva", "Nueva12345"))
                .andExpect(status().isOk());

        // El gateway rechaza los tokens con iat < corte.
        assertThat(Long.parseLong(redis.opsForValue().get(Sesiones.VALIDO_DESDE + usuario.getId()))).isGreaterThan(iat);
        // Ya estaba confirmada: no sale otro PerfilGuardado.
        assertThat(eventos.durante(Duration.ofMillis(800))).noneMatch(
                s -> s.type().equals(TiposEvento.PERFIL_GUARDADO) && s.aggregateId().equals(usuario.getId().toString()));
    }

    @Test
    void laRecuperacionRespondeIgualExistaONoLaCuentaYEsperaUnMinutoEntreCorreos() throws Exception {
        String inexistente = Escenario.correo();
        publicar("/api/auth/recuperacion", Map.of("correo", inexistente)).andExpect(status().isAccepted());

        UsuarioEntity usuario = escenario.usuario(Rol.CUSTOMER, null);
        publicar("/api/auth/recuperacion", Map.of("correo", usuario.getCorreo())).andExpect(status().isAccepted());
        publicar("/api/auth/recuperacion", Map.of("correo", usuario.getCorreo())).andExpect(status().isAccepted());

        assertThat(eventos.durante(Duration.ofSeconds(1)))
                .filteredOn(s -> s.type().equals(TiposEvento.RECUPERACION_SOLICITADA))
                .extracting(s -> s.payload().get("correo").asText())
                .containsOnlyOnce(usuario.getCorreo())
                .doesNotContain(inexistente);
    }

    @Test
    void elLoginRechazaIgualContrasenaEquivocadaCuentaInactivaYPendiente() throws Exception {
        UsuarioEntity activo = escenario.usuario(Rol.CUSTOMER, null);
        UsuarioEntity inactivo = escenario.usuario(Rol.CUSTOMER, null);
        jdbc.update("update usuario set activo = false where id = ?", inactivo.getId());
        UsuarioEntity pendiente = escenario.pendiente(Rol.CUSTOMER, null);

        for (Map<String, String> credenciales : new Map[] {
                Map.of("correo", activo.getCorreo(), "contrasena", "Equivocada1"),
                Map.of("correo", inactivo.getCorreo(), "contrasena", Escenario.CONTRASENA),
                Map.of("correo", pendiente.getCorreo(), "contrasena", Escenario.CONTRASENA),
                Map.of("correo", Escenario.correo(), "contrasena", Escenario.CONTRASENA)}) {
            publicar("/api/auth/login", credenciales)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Correo o contraseña incorrectos"));
        }
    }

    @Test
    void elLogoutRevocaSoloElTokenDeEsaSesion() throws Exception {
        UsuarioEntity usuario = escenario.usuario(Rol.BRANCH_ADMIN, escenario.sucursal());
        String jwt = leer(publicar("/api/auth/login", Map.of("correo", usuario.getCorreo(),
                "contrasena", Escenario.CONTRASENA)).andExpect(status().isOk())).get("token").asText();
        String jti = SignedJWT.parse(jwt).getJWTClaimsSet().getJWTID();

        // El token de otro usuario no sirve para cerrar esta sesión.
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + jwt)
                .with(como(escenario.usuario(Rol.CUSTOMER, null)))).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + jwt).with(como(usuario)))
                .andExpect(status().isNoContent());

        assertThat(redis.hasKey(Sesiones.REVOCADO + jti)).isTrue();
        assertThat(redis.getExpire(Sesiones.REVOCADO + jti)).isBetween(1L, Duration.ofHours(8).toSeconds());
    }

    @Test
    void elRegistroRechazaUnNitInvalidoOYaTomado() throws Exception {
        UsuarioEntity existente = escenario.usuario(Rol.CUSTOMER, null);
        String nit = Escenario.nit();
        jdbc.update("update perfil set nit = ? where usuario_id = ?", nit, existente.getId());
        Map<String, String> base = Map.of("correo", Escenario.correo(), "nombreCompleto", "Ana",
                "dpi", Escenario.dpi(), "telefono", "55551234", "direccion", "Zona 1");

        publicar("/api/auth/registro", con(base, "nit", "12A45")).andExpect(status().isUnprocessableEntity());
        publicar("/api/auth/registro", con(base, "nit", nit))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ese NIT ya pertenece a otra cuenta"));
        publicar("/api/auth/registro", con(base, "correo", existente.getCorreo().toUpperCase()))
                .andExpect(status().isConflict());
        // Faltan datos del perfil: el autorregistro los exige.
        publicar("/api/auth/registro", Map.of("correo", Escenario.correo(), "nombreCompleto", "Ana"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.dpi").exists());
    }

    /** Pide un enlace de recuperación y lo saca del evento E17, como lo haría notification. */
    private String enlaceDeRecuperacion(UsuarioEntity usuario) throws Exception {
        publicar("/api/auth/recuperacion", Map.of("correo", usuario.getCorreo())).andExpect(status().isAccepted());
        return eventos.esperar(TiposEvento.RECUPERACION_SOLICITADA,
                s -> s.aggregateId().equals(usuario.getId().toString())).payload().get("token").asText();
    }

    private ResultActions publicar(String ruta, Map<String, String> cuerpo) throws Exception {
        return mvc.perform(post(ruta).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(cuerpo)));
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private static Map<String, String> con(Map<String, String> base, String campo, String valor) {
        var copia = new java.util.HashMap<>(base);
        copia.put(campo, valor);
        return copia;
    }
}
