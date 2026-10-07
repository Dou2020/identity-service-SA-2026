package gt.cunoc.sa.identity.infrastructure.security;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.UsuarioEntity;

/**
 * Firma el JWT con HS256 (README, "Revocación de JWT"). Los claims son un contrato con el
 * gateway, que los convierte en X-User-Id, X-User-Roles, X-Sucursal-Id y X-Chofer-Id.
 *
 * HS256 y no RS256: solo identity y el gateway tienen el secreto, y ningún otro servicio
 * verifica el token (confían en los encabezados del gateway). Un par de llaves solo agregaría
 * archivos que montar.
 */
@Component
public class EmisorJwt {

    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_SUCURSAL = "sucursalId";
    public static final String CLAIM_CHOFER = "choferId";

    private static final int BYTES_MINIMOS = 32;   // 256 bits, lo que exige HS256

    private final JWSSigner firmante;
    private final JWSVerifier verificador;
    private final Duration duracion;

    public EmisorJwt(PropiedadesJwt propiedades) {
        byte[] secreto = decodificar(propiedades.secreto());
        try {
            this.firmante = new MACSigner(secreto);
            this.verificador = new MACVerifier(secreto);
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT_SECRET no sirve para HS256", e);
        }
        this.duracion = propiedades.duracion();
    }

    public record TokenEmitido(String token, String jti, Instant expiraEn) {
    }

    public record TokenLeido(UUID usuarioId, String jti, Instant expiraEn) {
    }

    public TokenEmitido emitir(UsuarioEntity usuario) {
        // iat y exp viajan en segundos; truncar aquí hace explícito lo que el gateway compara
        // contra auth:valido-desde.
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiraEn = ahora.plus(duracion);
        String jti = UUID.randomUUID().toString();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(usuario.getId().toString())
                .claim(CLAIM_ROLES, List.of(usuario.getRol().name()))
                .jwtID(jti)
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(expiraEn));
        // sucursalId solo para quien trabaja en una sucursal y la usa como alcance; el DRIVER
        // se acota por su choferId.
        if (usuario.getRol() == Rol.BRANCH_ADMIN || usuario.getRol() == Rol.CASHIER) {
            claims.claim(CLAIM_SUCURSAL, usuario.getSucursalId().toString());
        }
        if (usuario.getRol() == Rol.DRIVER && usuario.getChoferId() != null) {
            claims.claim(CLAIM_CHOFER, usuario.getChoferId().toString());
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims.build());
        try {
            jwt.sign(firmante);
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el JWT", e);
        }
        return new TokenEmitido(jwt.serialize(), jti, expiraEn);
    }

    /**
     * Lee un token que el gateway ya validó (el del logout). Se vuelve a verificar la firma:
     * en desarrollo el puerto 8081 está publicado y alguien podría mandar un token inventado.
     */
    public Optional<TokenLeido> leer(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verificador)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getSubject() == null || claims.getJWTID() == null || claims.getExpirationTime() == null) {
                return Optional.empty();
            }
            return Optional.of(new TokenLeido(UUID.fromString(claims.getSubject()), claims.getJWTID(),
                    claims.getExpirationTime().toInstant()));
        } catch (ParseException | JOSEException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration getDuracion() {
        return duracion;
    }

    /** Falla al arrancar con un mensaje claro: nunca firmar con un secreto vacío o corto. */
    static byte[] decodificar(String secreto) {
        if (secreto == null || secreto.isBlank()) {
            throw new IllegalStateException("Falta JWT_SECRET (generarlo con: openssl rand -base64 32)");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secreto.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET debe estar en Base64 (openssl rand -base64 32)", e);
        }
        if (bytes.length < BYTES_MINIMOS) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 256 bits (32 bytes); tiene "
                    + bytes.length * 8);
        }
        return bytes;
    }
}
