package gt.cunoc.sa.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.SucursalEntity;
import gt.cunoc.sa.identity.domain.SucursalEntity.DatosSucursal;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.infrastructure.security.EmisorJwt.TokenEmitido;

/** Los claims son un contrato con el gateway: si cambian aquí, el gateway deja de entenderlos. */
class EmisorJwtTest {

    private static final String SECRETO = Base64.getEncoder().encodeToString(new byte[32]);

    private final EmisorJwt emisor = new EmisorJwt(new PropiedadesJwt(SECRETO, Duration.ofHours(8)));
    private final SucursalEntity sucursal = SucursalEntity.nueva(new DatosSucursal("XELA", "Quetzaltenango",
            "Quetzaltenango", "Quetzaltenango", "Zona 1", null, BigDecimal.ONE, BigDecimal.ONE), Instant.now());

    @Test
    void firmaHs256ConLosClaimsQueLeeElGateway() throws Exception {
        UsuarioEntity admin = UsuarioEntity.confirmado("jefe@correo.gt", "Jefe", Rol.BRANCH_ADMIN, sucursal, "hash",
                Instant.now());

        TokenEmitido emitido = emisor.emitir(admin);

        SignedJWT jwt = SignedJWT.parse(emitido.token());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(Base64.getDecoder().decode(SECRETO)))).isTrue();
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(admin.getId().toString());
        assertThat(claims.getStringListClaim("roles")).containsExactly("BRANCH_ADMIN");
        assertThat(claims.getStringClaim("sucursalId")).isEqualTo(sucursal.getId().toString());
        assertThat(claims.getClaim("choferId")).isNull();
        assertThat(claims.getJWTID()).isEqualTo(emitido.jti());
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofHours(8));
    }

    @Test
    void elChoferLlevaSuChoferIdYNoLaSucursal() throws Exception {
        String choferId = UUID.randomUUID().toString();
        UsuarioEntity chofer = UsuarioEntity.pendienteDeChofer(choferId, 1, "chofer@correo.gt", "Chofer", sucursal, "h",
                Instant.now());

        JWTClaimsSet claims = SignedJWT.parse(emisor.emitir(chofer).token()).getJWTClaimsSet();

        assertThat(claims.getStringClaim("choferId")).isEqualTo(choferId);
        assertThat(claims.getClaim("sucursalId")).isNull();
    }

    /** El id de fleet es numérico: tiene que viajar como string, que es lo que lee el gateway. */
    @Test
    void elIdNumericoDeFleetViajaComoTexto() throws Exception {
        UsuarioEntity chofer = UsuarioEntity.pendienteDeChofer("3", 1, "chofer3@correo.gt", "Chofer", sucursal, "h",
                Instant.now());

        JWTClaimsSet claims = SignedJWT.parse(emisor.emitir(chofer).token()).getJWTClaimsSet();

        assertThat(claims.getClaim("choferId")).isInstanceOf(String.class).isEqualTo("3");
        assertThat(claims.getStringClaim("choferId")).isEqualTo("3");
    }

    @Test
    void leeSoloTokensFirmadosConSuSecreto() {
        UsuarioEntity cliente = UsuarioEntity.confirmado("cliente@correo.gt", "Cliente", Rol.CUSTOMER, null, "h",
                Instant.now());
        TokenEmitido emitido = emisor.emitir(cliente);
        EmisorJwt otro = new EmisorJwt(new PropiedadesJwt(
                Base64.getEncoder().encodeToString("otro-secreto-de-32-bytes-o-mas!!".getBytes()), Duration.ofHours(8)));

        assertThat(emisor.leer(emitido.token())).hasValueSatisfying(t -> {
            assertThat(t.usuarioId()).isEqualTo(cliente.getId());
            assertThat(t.jti()).isEqualTo(emitido.jti());
        });
        assertThat(otro.leer(emitido.token())).isEmpty();
        assertThat(emisor.leer("no-es-un-jwt")).isEmpty();
    }

    @Test
    void noArrancaConUnSecretoCortoOQueNoEsBase64() {
        String corto = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new EmisorJwt(new PropiedadesJwt(corto, Duration.ofHours(8))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("256 bits");
        assertThatThrownBy(() -> new EmisorJwt(new PropiedadesJwt("no es base64 ¿?", Duration.ofHours(8))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64");
        assertThatThrownBy(() -> new EmisorJwt(new PropiedadesJwt("", Duration.ofHours(8))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Falta JWT_SECRET");
    }
}
