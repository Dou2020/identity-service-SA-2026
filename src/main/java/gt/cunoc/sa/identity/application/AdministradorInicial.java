package gt.cunoc.sa.identity.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import gt.cunoc.sa.identity.api.perfil.PerfilRepository;
import gt.cunoc.sa.identity.api.usuario.UsuarioRepository;
import gt.cunoc.sa.identity.domain.Nit;
import gt.cunoc.sa.identity.domain.PerfilEntity;
import gt.cunoc.sa.identity.domain.Rol;
import gt.cunoc.sa.identity.domain.UsuarioEntity;

/**
 * El primer SYS_ADMIN, que nadie más puede crear. Sale de ADMIN_CORREO y ADMIN_CONTRASENA y nace
 * ya confirmado: no puede usar el enlace de bienvenida porque el correo lo envía
 * notification-service, que puede no estar desplegado todavía. Se descartó una migración con
 * un hash fijo: la contraseña quedaría en el repositorio y llegaría igual a producción.
 *
 * Solo actúa si no existe ningún SYS_ADMIN, así que cambiar las variables después no hace nada.
 */
@Component
public class AdministradorInicial implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdministradorInicial.class);

    private final UsuarioRepository usuarios;
    private final PerfilRepository perfiles;
    private final PasswordEncoder codificador;
    private final PropiedadesIdentidad propiedades;
    private final TransactionTemplate transaccion;

    public AdministradorInicial(UsuarioRepository usuarios, PerfilRepository perfiles, PasswordEncoder codificador,
            PropiedadesIdentidad propiedades, TransactionTemplate transaccion) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.codificador = codificador;
        this.propiedades = propiedades;
        this.transaccion = transaccion;
    }

    @Override
    public void run(ApplicationArguments args) {
        transaccion.executeWithoutResult(estado -> {
            if (usuarios.existsByRol(Rol.SYS_ADMIN)) {
                return;
            }
            PropiedadesIdentidad.AdminInicial admin = propiedades.adminInicial();
            if (admin == null || vacio(admin.correo()) || vacio(admin.contrasena())) {
                log.warn("No hay ningún SYS_ADMIN y faltan ADMIN_CORREO o ADMIN_CONTRASENA: nadie podrá administrar");
                return;
            }
            Instant ahora = Instant.now();
            UsuarioEntity usuario = UsuarioEntity.confirmado(admin.correo(), admin.nombre(), Rol.SYS_ADMIN, null,
                    codificador.encode(admin.contrasena()), ahora);
            usuarios.save(usuario);
            perfiles.save(new PerfilEntity(usuario, Nit.CONSUMIDOR_FINAL, null, null, null, ahora));
            log.info("Se creó el administrador inicial {}", usuario.getCorreo());
        });
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
