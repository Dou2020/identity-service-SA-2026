package gt.cunoc.sa.identity.infrastructure.messaging.eventos;

/** Routing keys (dominio.agregado.evento) de lo que identity publica y consume. */
public final class TiposEvento {

    public static final String SUCURSAL_GUARDADA = "identity.sucursal.guardada";             // E1
    public static final String CONFIGURACION_ACTUALIZADA = "identity.configuracion.actualizada"; // E2
    public static final String USUARIO_CREADO = "identity.usuario.creado";                   // E3
    public static final String PERFIL_GUARDADO = "identity.perfil.guardado";                 // E4
    public static final String RECUPERACION_SOLICITADA = "identity.recuperacion.solicitada"; // E17

    public static final String CHOFER_GUARDADO = "fleet.chofer.guardado";                    // E5 (consume)

    private TiposEvento() {
    }
}
