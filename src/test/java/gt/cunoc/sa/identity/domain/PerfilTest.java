package gt.cunoc.sa.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * La versión del perfil es el aggregateVersion de PerfilGuardado (E4): solo debe subir cuando
 * algo cambió, o customer reprocesaría el enlace por NIT con cada guardado sin cambios.
 */
class PerfilTest {

    private final Instant ahora = Instant.now();
    private final UsuarioEntity usuario = UsuarioEntity.pendiente("ana@correo.gt", "Ana", Rol.CUSTOMER, null, "hash",
            ahora);

    @Test
    void guardarSinCambiosNoReportaCambio() {
        PerfilEntity perfil = new PerfilEntity(usuario, "12345679", "1234567890123", "55551234", "Zona 1", ahora);

        assertThat(perfil.actualizar("12345679", "1234567890123", "55551234", "Zona 1", ahora)).isFalse();
        assertThat(perfil.getVersion()).isZero();
    }

    @Test
    void cambiarUnDatoReportaCambio() {
        PerfilEntity perfil = new PerfilEntity(usuario, "12345679", "1234567890123", "55551234", "Zona 1", ahora);

        assertThat(perfil.actualizar(Nit.CONSUMIDOR_FINAL, "1234567890123", "55551234", "Zona 1", ahora)).isTrue();
        assertThat(perfil.getNit()).isEqualTo(Nit.CONSUMIDOR_FINAL);
        assertThat(perfil.nuevaVersion(ahora)).isEqualTo(1);
    }

    @Test
    void losVaciosSeGuardanComoNuloYElPerfilQuedaIncompleto() {
        PerfilEntity perfil = new PerfilEntity(usuario, Nit.CONSUMIDOR_FINAL, " ", "55551234", "", ahora);

        assertThat(perfil.getDpi()).isNull();
        assertThat(perfil.getDireccion()).isNull();
        assertThat(perfil.isCompleto()).isFalse();
        // null y "  " son lo mismo: no es un cambio
        assertThat(perfil.actualizar(Nit.CONSUMIDOR_FINAL, null, "55551234", "  ", ahora)).isFalse();
    }

    @Test
    void laPrimeraContrasenaConfirmaLaCuentaYLasSiguientesNo() {
        assertThat(usuario.isConfirmado()).isFalse();
        assertThat(usuario.definirContrasena("hash1", ahora)).isTrue();
        assertThat(usuario.isConfirmado()).isTrue();
        assertThat(usuario.definirContrasena("hash2", ahora)).isFalse();
    }
}
