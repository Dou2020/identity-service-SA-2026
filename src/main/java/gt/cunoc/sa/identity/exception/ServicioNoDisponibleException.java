package gt.cunoc.sa.identity.exception;

/**
 * Una dependencia de infraestructura (Redis) no respondió. Sale como 503 y la transacción se
 * revierte: cerrar sesión, desactivar o cambiar un usuario y emitir un enlace no cambian nada
 * si no pueden escribir en Redis (README, "Sesiones y límites con Redis").
 */
public class ServicioNoDisponibleException extends RuntimeException {

    public ServicioNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
