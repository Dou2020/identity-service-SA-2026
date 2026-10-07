package gt.cunoc.sa.identity.domain;

/** NIT con formato o dígito verificador inválido; sale como 422. */
public class NitInvalidoException extends RuntimeException {

    public NitInvalidoException(String mensaje) {
        super(mensaje);
    }
}
