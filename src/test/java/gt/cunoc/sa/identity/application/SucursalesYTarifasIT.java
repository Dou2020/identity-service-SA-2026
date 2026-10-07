package gt.cunoc.sa.identity.application;

import static gt.cunoc.sa.identity.Escenario.como;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import gt.cunoc.sa.identity.ColaDePrueba;
import gt.cunoc.sa.identity.Escenario;
import gt.cunoc.sa.identity.PruebaDeIntegracion;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.infrastructure.messaging.Sobre;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;

@PruebaDeIntegracion
class SucursalesYTarifasIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Escenario escenario;
    @Autowired
    ObjectMapper json;
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
    void laSucursalNaceConSuPrimerAdministradorYEmiteSucursalGuardadaYUsuarioCreado() throws Exception {
        String correoAdmin = Escenario.correo();
        JsonNode creada = leer(enviar(post("/api/sucursales"), Map.of("sucursal", sucursal("SM1"),
                "administrador", Map.of("correo", correoAdmin, "nombreCompleto", "Jefa San Marcos")),
                escenario.admin()).andExpect(status().isCreated()));
        String id = creada.at("/sucursal/id").asText();
        assertThat(creada.at("/administrador/rol").asText()).isEqualTo("BRANCH_ADMIN");
        assertThat(creada.at("/administrador/sucursalId").asText()).isEqualTo(id);

        Sobre e1 = eventos.esperar(TiposEvento.SUCURSAL_GUARDADA, s -> s.aggregateId().equals(id));
        assertThat(e1.aggregateVersion()).isZero();
        assertThat(e1.payload().get("latitud").decimalValue()).isEqualByComparingTo("14.963889");
        Sobre e3 = eventos.esperar(TiposEvento.USUARIO_CREADO,
                s -> s.payload().get("correo").asText().equals(correoAdmin));
        assertThat(e3.payload().get("sucursalId").asText()).isEqualTo(id);

        // Editar sube la versión de E1: los consumidores descartan una réplica vieja.
        Map<String, Object> editada = sucursal("SM1");
        editada.put("nombre", "San Marcos centro " + codigo());
        enviar(put("/api/sucursales/{id}", id), editada, escenario.admin()).andExpect(status().isOk());
        assertThat(eventos.esperar(TiposEvento.SUCURSAL_GUARDADA,
                s -> s.aggregateId().equals(id) && s.aggregateVersion() == 1).payload().get("nombre").asText())
                .startsWith("San Marcos centro");

        enviar(patch("/api/sucursales/{id}/estado", id), Map.of("activa", false), escenario.admin())
                .andExpect(status().isOk());
        assertThat(eventos.esperar(TiposEvento.SUCURSAL_GUARDADA,
                s -> s.aggregateId().equals(id) && s.aggregateVersion() == 2).payload().get("activa").asBoolean())
                .isFalse();
    }

    @Test
    void siElAdministradorNoSePuedeCrearTampocoSeCreaLaSucursal() throws Exception {
        String correoTomado = escenario.usuario(Rol.CUSTOMER, null).getCorreo();
        Map<String, Object> datos = sucursal("HUE");

        enviar(post("/api/sucursales"), Map.of("sucursal", datos,
                "administrador", Map.of("correo", correoTomado, "nombreCompleto", "Jefe")), escenario.admin())
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from sucursal where codigo = ?", Integer.class,
                datos.get("codigo"))).isZero();
    }

    @Test
    void elCodigoDeSucursalEsUnico() throws Exception {
        Map<String, Object> datos = sucursal("REU");
        enviar(post("/api/sucursales"), Map.of("sucursal", datos, "administrador",
                Map.of("correo", Escenario.correo(), "nombreCompleto", "Uno")), escenario.admin())
                .andExpect(status().isCreated());
        datos.put("nombre", "Otro nombre " + codigo());

        enviar(post("/api/sucursales"), Map.of("sucursal", datos, "administrador",
                Map.of("correo", Escenario.correo(), "nombreCompleto", "Dos")), escenario.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ya existe una sucursal con ese código"));
    }

    @Test
    void soloElSysAdminAdministraSucursales() throws Exception {
        UsuarioActual jefe = Escenario.actual(escenario.usuario(Rol.BRANCH_ADMIN, escenario.sucursal()));
        enviar(post("/api/sucursales"), Map.of("sucursal", sucursal("X"),
                "administrador", Map.of("correo", Escenario.correo(), "nombreCompleto", "X")), jefe)
                .andExpect(status().isForbidden());
    }

    @Test
    void lasTarifasSonVersionesYLaVigenteDependeDeLaFecha() throws Exception {
        enviar(post("/api/tarifas"), Map.of("tipo", "ALQUILER_DIA", "valor", "500.00", "vigenteDesde", "2031-01-01"),
                escenario.admin()).andExpect(status().isCreated());
        JsonNode segunda = leer(enviar(post("/api/tarifas"), Map.of("tipo", "ALQUILER_DIA", "valor", "650.50",
                "vigenteDesde", "2031-06-01"), escenario.admin()).andExpect(status().isCreated()));

        mvc.perform(get("/api/tarifas/vigentes").param("fecha", "2031-03-15").with(como(escenario.admin())))
                .andExpect(jsonPath("$[?(@.tipo == 'ALQUILER_DIA')].valor").value(500.0));
        mvc.perform(get("/api/tarifas/vigentes").param("fecha", "2031-07-01").with(como(escenario.admin())))
                .andExpect(jsonPath("$[?(@.tipo == 'ALQUILER_DIA')].valor").value(650.5));

        Sobre e2 = eventos.esperar(TiposEvento.CONFIGURACION_ACTUALIZADA,
                s -> s.aggregateVersion() == segunda.get("id").asLong());
        assertThat(e2.aggregateId()).isEqualTo("ALQUILER_DIA");
        assertThat(e2.payload().get("vigenteDesde").asText()).isEqualTo("2031-06-01");

        // Misma fecha, mismo tipo: la versión ya existe.
        enviar(post("/api/tarifas"), Map.of("tipo", "ALQUILER_DIA", "valor", "1", "vigenteDesde", "2031-06-01"),
                escenario.admin()).andExpect(status().isConflict());
    }

    @Test
    void elBranchAdminConsultaLasTarifasVigentesPeroNoLasCambia() throws Exception {
        UsuarioActual jefe = Escenario.actual(escenario.usuario(Rol.BRANCH_ADMIN, escenario.sucursal()));
        mvc.perform(get("/api/tarifas/vigentes").with(como(jefe))).andExpect(status().isOk());
        enviar(post("/api/tarifas"), Map.of("tipo", "DEPRECIACION_KM", "valor", "1.25"), jefe)
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> sucursal(String prefijo) {
        String codigo = prefijo + codigo();
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("codigo", codigo);
        datos.put("nombre", "Sucursal " + codigo);
        datos.put("departamento", "San Marcos");
        datos.put("municipio", "San Marcos");
        datos.put("direccion", "4a calle 5-20 zona 1");
        datos.put("latitud", new BigDecimal("14.963889"));
        datos.put("longitud", new BigDecimal("-91.794722"));
        return datos;
    }

    private static String codigo() {
        return Integer.toString((int) (Math.random() * 100_000));
    }

    private ResultActions enviar(MockHttpServletRequestBuilder peticion, Map<String, ?> cuerpo, UsuarioActual actor)
            throws Exception {
        return mvc.perform(peticion.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cuerpo)).with(como(actor)));
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return json.readTree(resultado.andReturn().getResponse().getContentAsString());
    }
}
