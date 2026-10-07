package gt.cunoc.sa.identity.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import gt.cunoc.sa.identity.api.usuario.UsuarioRepository;

/**
 * Borra los autorregistros de CUSTOMER que nadie confirmó a tiempo y libera su correo y su NIT.
 * No contradice "los usuarios no se eliminan": esa cuenta nunca inició sesión y, como su
 * PerfilGuardado no salió, ningún otro servicio la conoce. Las cuentas que crea un admin o E5
 * no vencen: las referencian la sucursal y el chofer.
 */
@Component
public class PurgaRegistros {

    private static final Logger log = LoggerFactory.getLogger(PurgaRegistros.class);

    private final UsuarioRepository usuarios;
    private final PropiedadesIdentidad propiedades;

    public PurgaRegistros(UsuarioRepository usuarios,
            PropiedadesIdentidad propiedades) {
        this.usuarios = usuarios;
        this.propiedades = propiedades;
    }

    /** Cada hora; un registro vive entre 72 y 73 h, y su enlace vence a las 72. */
    @Scheduled(cron = "${identity.purga.cron:0 15 * * * *}")
    @Transactional
    public int purgar() {
        int borrados = usuarios.borrarRegistrosSinConfirmar(
                Instant.now().minus(propiedades.enlaces().bienvenida()));
        if (borrados > 0) {
            log.info("Se borraron {} autorregistros sin confirmar", borrados);
        }
        return borrados;
    }
}
