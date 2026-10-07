package gt.cunoc.sa.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import gt.cunoc.sa.identity.Escenario;
import gt.cunoc.sa.identity.PruebaDeIntegracion;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.UsuarioEntity;

@PruebaDeIntegracion
class PurgaRegistrosIT {

    @Autowired
    PurgaRegistros purga;
    @Autowired
    Escenario escenario;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void borraLosAutorregistrosSinConfirmarDeMasDe72HorasYLiberaSuNit() {
        String nit = Escenario.nit();
        UsuarioEntity viejo = escenario.pendiente(Rol.CUSTOMER, null);
        UsuarioEntity reciente = escenario.pendiente(Rol.CUSTOMER, null);
        UsuarioEntity confirmado = escenario.usuario(Rol.CUSTOMER, null);
        UsuarioEntity cajeroPendiente = escenario.pendiente(Rol.CASHIER, escenario.sucursal());
        jdbc.update("update perfil set nit = ? where usuario_id = ?", nit, viejo.getId());
        jdbc.update("update usuario set creado_en = now() - interval '73 hours' where id in (?, ?, ?)",
                viejo.getId(), confirmado.getId(), cajeroPendiente.getId());

        assertThat(purga.purgar()).isGreaterThanOrEqualTo(1);

        assertThat(existe(viejo)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from perfil where nit = ?", Integer.class, nit)).isZero();
        // Ni los recientes, ni los confirmados, ni las cuentas que crea un admin.
        assertThat(existe(reciente)).isTrue();
        assertThat(existe(confirmado)).isTrue();
        assertThat(existe(cajeroPendiente)).isTrue();
    }

    private boolean existe(UsuarioEntity usuario) {
        return jdbc.queryForObject("select count(*) from usuario where id = ?", Integer.class, usuario.getId()) == 1;
    }
}
