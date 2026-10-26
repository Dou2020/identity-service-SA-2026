package gt.cunoc.sa.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Si el contexto levanta, Flyway aplicó las migraciones y Hibernate validó que las entidades
 * coinciden con el esquema (ddl-auto=validate falla al arrancar si no).
 */
@PruebaDeIntegracion
class IdentityApplicationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aplicaLasMigraciones() {
        List<String> tablas = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(tablas).contains("sucursal", "usuario", "perfil", "tarifa", "outbox_event", "processed_event");
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(3);
    }

    /** V3: el id de fleet es texto, y cambiar el tipo no se llevó la UQ ni el CHECK de la columna. */
    @Test
    void elChoferIdEsTextoYConservaSusRestricciones() {
        assertThat(jdbc.queryForMap("""
                select data_type, character_maximum_length from information_schema.columns
                where table_name = 'usuario' and column_name = 'chofer_id'"""))
                .containsEntry("data_type", "character varying")
                .containsEntry("character_maximum_length", 64);
        assertThat(jdbc.queryForList("""
                select conname from pg_constraint
                where conrelid = 'usuario'::regclass and conname in ('usuario_chofer_uq', 'usuario_chofer_solo_driver')""",
                String.class)).containsExactlyInAnyOrder("usuario_chofer_uq", "usuario_chofer_solo_driver");
    }

    @Test
    void creaElAdministradorInicialConfirmado() {
        assertThat(jdbc.queryForObject("""
                select count(*) from usuario
                where correo = ? and rol = 'SYS_ADMIN' and activo and confirmado_en is not null""",
                Integer.class, Escenario.ADMIN_INICIAL)).isEqualTo(1);
    }
}
