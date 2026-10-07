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
                "select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(2);
    }

    @Test
    void creaElAdministradorInicialConfirmado() {
        assertThat(jdbc.queryForObject("""
                select count(*) from usuario
                where correo = ? and rol = 'SYS_ADMIN' and activo and confirmado_en is not null""",
                Integer.class, Escenario.ADMIN_INICIAL)).isEqualTo(1);
    }
}
