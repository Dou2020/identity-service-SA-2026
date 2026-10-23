package gt.cunoc.sa.identity.application;

import static gt.cunoc.sa.identity.Escenario.como;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import gt.cunoc.sa.identity.Escenario;
import gt.cunoc.sa.identity.PruebaDeIntegracion;
import gt.cunoc.sa.identity.api.usuario.UsuarioService;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.exception.ErrorDeNegocio;
import gt.cunoc.sa.identity.infrastructure.redis.Sesiones;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;

/**
 * Las invariantes de identity y la autorización por rol y por sucursal (README, "Pruebas" →
 * "Autorización por servicio").
 */
@PruebaDeIntegracion
class UsuariosIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    Escenario escenario;
    @Autowired
    UsuarioService servicio;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;

    @Test
    void noSeDesactivaAlUltimoSysAdminActivo() throws Exception {
        UsuarioEntity unico = escenario.usuario(Rol.SYS_ADMIN, null);
        conSoloEstosSysAdmins(List.of(unico.getId()), () -> {
            enviar(patch("/api/usuarios/{id}/estado", unico.getId()), Map.of("activo", false), escenario.admin())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("Debe quedar al menos un administrador del sistema activo"));
            enviar(patch("/api/usuarios/{id}", unico.getId()), Map.of("rol", "CASHIER",
                    "sucursalId", escenario.sucursal().getId().toString()), escenario.admin())
                    .andExpect(status().isConflict());
            return null;
        });
    }

    /**
     * Dos SYS_ADMIN activos y dos desactivaciones simultáneas: sin el SELECT … FOR UPDATE las dos
     * verían "queda otro" y el sistema se quedaría sin administrador.
     */
    @Test
    void dosDesactivacionesSimultaneasDejanAlMenosUnSysAdmin() throws Exception {
        UsuarioEntity a = escenario.usuario(Rol.SYS_ADMIN, null);
        UsuarioEntity b = escenario.usuario(Rol.SYS_ADMIN, null);
        UsuarioActual actor = escenario.admin();
        conSoloEstosSysAdmins(List.of(a.getId(), b.getId()), () -> {
            ExecutorService hilos = Executors.newFixedThreadPool(2);
            CountDownLatch salida = new CountDownLatch(1);
            List<Future<String>> resultados = new ArrayList<>();
            for (UsuarioEntity objetivo : List.of(a, b)) {
                resultados.add(hilos.submit(() -> {
                    Escenario.autenticar(actor);
                    salida.await();
                    try {
                        servicio.cambiarEstado(objetivo.getId(), false, actor);
                        return "desactivado";
                    } catch (ErrorDeNegocio e) {
                        return "rechazado";
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            salida.countDown();
            List<String> desenlaces = new ArrayList<>();
            for (Future<String> r : resultados) {
                desenlaces.add(r.get());
            }
            hilos.shutdown();

            assertThat(desenlaces).containsExactlyInAnyOrder("desactivado", "rechazado");
            assertThat(jdbc.queryForObject("select count(*) from usuario where id in (?, ?) and activo",
                    Integer.class, a.getId(), b.getId())).isEqualTo(1);
            return null;
        });
    }

    @Test
    void unaSucursalConservaAlMenosUnAdministrador() throws Exception {
        SucursalEntity sucursal = escenario.sucursal();
        UsuarioEntity primero = escenario.usuario(Rol.BRANCH_ADMIN, sucursal);

        enviar(patch("/api/usuarios/{id}/estado", primero.getId()), Map.of("activo", false), escenario.admin())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La sucursal debe conservar al menos un administrador activo"));

        escenario.usuario(Rol.BRANCH_ADMIN, sucursal);
        enviar(patch("/api/usuarios/{id}/estado", primero.getId()), Map.of("activo", false), escenario.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
        // Desactivar cierra sus sesiones al instante.
        assertThat(redis.hasKey(Sesiones.VALIDO_DESDE + primero.getId())).isTrue();
    }

    @Test
    void elBranchAdminCreaCajerosSoloEnSuSucursal() throws Exception {
        SucursalEntity suya = escenario.sucursal();
        UsuarioEntity jefe = escenario.usuario(Rol.BRANCH_ADMIN, suya);

        enviar(post("/api/usuarios"), Map.of("correo", Escenario.correo(), "nombreCompleto", "Cajero",
                "rol", "CASHIER", "sucursalId", escenario.sucursal().getId().toString()), Escenario.actual(jefe))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sucursalId").value(suya.getId().toString()))
                .andExpect(jsonPath("$.confirmado").value(false))
                .andExpect(jsonPath("$.perfil.completo").value(false));

        enviar(post("/api/usuarios"), Map.of("correo", Escenario.correo(), "nombreCompleto", "Otro jefe",
                "rol", "BRANCH_ADMIN", "sucursalId", suya.getId().toString()), Escenario.actual(jefe))
                .andExpect(status().isForbidden());
    }

    @Test
    void elSysAdminNoCreaCajerosNiClientes() throws Exception {
        for (String rol : List.of("CASHIER", "CUSTOMER", "DRIVER")) {
            enviar(post("/api/usuarios"), Map.of("correo", Escenario.correo(), "nombreCompleto", "X", "rol", rol,
                    "sucursalId", escenario.sucursal().getId().toString()), escenario.admin())
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void elBranchAdminSoloVeLosUsuariosDeSuSucursal() throws Exception {
        SucursalEntity suya = escenario.sucursal();
        SucursalEntity otra = escenario.sucursal();
        UsuarioEntity jefe = escenario.usuario(Rol.BRANCH_ADMIN, suya);
        escenario.usuario(Rol.CASHIER, suya);
        UsuarioEntity ajeno = escenario.usuario(Rol.CASHIER, otra);

        // Aunque pida la otra sucursal, la consulta se filtra por la suya.
        mvc.perform(get("/api/usuarios").param("sucursalId", otra.getId().toString()).with(como(jefe)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].sucursalId", Matchers.everyItem(Matchers.is(suya.getId().toString()))))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mvc.perform(get("/api/usuarios/{id}", ajeno.getId()).with(como(jefe))).andExpect(status().isForbidden());
        enviar(patch("/api/usuarios/{id}/estado", ajeno.getId()), Map.of("activo", false), Escenario.actual(jefe))
                .andExpect(status().isForbidden());
    }

    @Test
    void cadaRolSoloEntraASusEndpoints() throws Exception {
        SucursalEntity sucursal = escenario.sucursal();
        for (Rol rol : List.of(Rol.CASHIER, Rol.DRIVER, Rol.CUSTOMER)) {
            UsuarioEntity usuario = escenario.usuario(rol, rol.requiereSucursal() ? sucursal : null);
            mvc.perform(get("/api/usuarios").with(como(usuario))).andExpect(status().isForbidden());
            mvc.perform(get("/api/tarifas").with(como(usuario))).andExpect(status().isForbidden());
            mvc.perform(get("/api/sucursales").with(como(usuario))).andExpect(status().isOk());
            mvc.perform(get("/api/perfil").with(como(usuario))).andExpect(status().isOk());
        }
        UsuarioEntity jefe = escenario.usuario(Rol.BRANCH_ADMIN, sucursal);
        enviar(patch("/api/usuarios/{id}", jefe.getId()), Map.of("rol", "SYS_ADMIN"), Escenario.actual(jefe))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/usuarios")).andExpect(status().isUnauthorized());
    }

    @Test
    void cambiarElRolCierraLasSesionesYSoloVaEntreRolesDePersonal() throws Exception {
        SucursalEntity sucursal = escenario.sucursal();
        UsuarioEntity cajero = escenario.usuario(Rol.CASHIER, sucursal);

        enviar(patch("/api/usuarios/{id}", cajero.getId()),
                Map.of("rol", "BRANCH_ADMIN", "sucursalId", sucursal.getId().toString()), escenario.admin())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("BRANCH_ADMIN"));
        assertThat(redis.hasKey(Sesiones.VALIDO_DESDE + cajero.getId())).isTrue();

        UsuarioEntity cliente = escenario.usuario(Rol.CUSTOMER, null);
        enviar(patch("/api/usuarios/{id}", cliente.getId()), Map.of("rol", "SYS_ADMIN"), escenario.admin())
                .andExpect(status().isUnprocessableEntity());
        UsuarioEntity chofer = escenario.usuario(Rol.DRIVER, sucursal);
        enviar(patch("/api/usuarios/{id}/estado", chofer.getId()), Map.of("activo", false), escenario.admin())
                .andExpect(status().isConflict());
    }

    @Test
    void elDetalleTraeElPerfil() throws Exception {
        UsuarioEntity cajero = escenario.usuario(Rol.CASHIER, escenario.sucursal());

        mvc.perform(get("/api/usuarios/{id}", cajero.getId()).with(como(escenario.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfil.nit").value("CF"))
                .andExpect(jsonPath("$.perfil.telefono").value("55551234"));
        mvc.perform(get("/api/usuarios/{id}", UUID.randomUUID()).with(como(escenario.admin())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/usuarios").param("sort", "noExiste").with(como(escenario.admin())))
                .andExpect(status().isBadRequest());
    }

    /**
     * Las invariantes cuentan a todos los SYS_ADMIN de la base, que comparten todas las pruebas:
     * se desactivan los demás mientras dura la prueba y se restauran al final.
     */
    private void conSoloEstosSysAdmins(List<UUID> quedan, Callable<Void> prueba) throws Exception {
        List<UUID> apartados = jdbc.queryForList(
                "select id from usuario where rol = 'SYS_ADMIN' and activo and id <> all (?)", UUID.class,
                (Object) quedan.toArray(UUID[]::new));
        apartados.forEach(id -> jdbc.update("update usuario set activo = false where id = ?", id));
        try {
            prueba.call();
        } finally {
            apartados.forEach(id -> jdbc.update("update usuario set activo = true where id = ?", id));
        }
    }

    private ResultActions enviar(MockHttpServletRequestBuilder peticion, Map<String, ?> cuerpo, UsuarioActual actor)
            throws Exception {
        return mvc.perform(peticion.contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(cuerpo)).with(como(actor)));
    }
}
