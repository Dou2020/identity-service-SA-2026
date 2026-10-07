package gt.cunoc.sa.identity.application;

import static gt.cunoc.sa.identity.Escenario.como;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import gt.cunoc.sa.identity.ContenedoresDePrueba;
import gt.cunoc.sa.identity.Escenario;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.security.EmisorJwt;

/**
 * Sin Redis, lo que tiene que escribir en Redis responde 503 y no cambia nada (README, "Manejo
 * de fallos"); el login, que no lo usa, sigue funcionando. Contexto aparte: Redis apunta a un
 * puerto cerrado.
 */
@SpringBootTest(properties = {"prueba.redis-caido=true", "spring.data.redis.port=1",
        "management.health.redis.enabled=false"})
@AutoConfigureMockMvc
@Import(ContenedoresDePrueba.class)
class RedisCaidoIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Escenario escenario;
    @Autowired
    EmisorJwt emisor;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void cerrarSesionYPedirUnEnlaceResponden503() throws Exception {
        UsuarioEntity usuario = escenario.usuario(Rol.CUSTOMER, null);

        mvc.perform(post("/api/auth/logout").with(como(usuario))
                .header("Authorization", "Bearer " + emisor.emitir(usuario).token()))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/auth/recuperacion").contentType(MediaType.APPLICATION_JSON)
                .content("{\"correo\":\"" + usuario.getCorreo() + "\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"correo\":\"" + usuario.getCorreo() + "\",\"contrasena\":\"" + Escenario.CONTRASENA + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void desactivarSinPoderCerrarLasSesionesNoDesactiva() throws Exception {
        SucursalEntity sucursal = escenario.sucursal();
        escenario.usuario(Rol.BRANCH_ADMIN, sucursal);
        UsuarioEntity cajero = escenario.usuario(Rol.CASHIER, sucursal);

        mvc.perform(patch("/api/usuarios/{id}/estado", cajero.getId()).with(como(escenario.admin()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"activo\":false}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(jdbc.queryForObject("select activo from usuario where id = ?", Boolean.class, cajero.getId()))
                .isTrue();
    }
}
