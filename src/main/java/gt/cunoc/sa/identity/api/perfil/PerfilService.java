package gt.cunoc.sa.identity.api.perfil;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gt.cunoc.sa.identity.api.perfil.dto.ActualizarPerfilRequest;
import gt.cunoc.sa.identity.api.perfil.dto.PerfilResponse;
import gt.cunoc.sa.identity.domain.Nit;
import gt.cunoc.sa.identity.domain.PerfilEntity;
import gt.cunoc.sa.identity.domain.UsuarioEntity;
import gt.cunoc.sa.identity.exception.ErrorDeNegocio;
import gt.cunoc.sa.identity.infrastructure.messaging.Outbox;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.PerfilGuardado;
import gt.cunoc.sa.identity.infrastructure.messaging.eventos.TiposEvento;
import gt.cunoc.sa.identity.infrastructure.security.UsuarioActual;

/** Cuenta y perfil propios: cada usuario solo ve y edita el suyo (sale de X-User-Id). */
@Service
public class PerfilService {

    private final PerfilRepository perfiles;
    private final Outbox outbox;

    public PerfilService(PerfilRepository perfiles, Outbox outbox) {
        this.perfiles = perfiles;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public PerfilResponse obtener(UsuarioActual actual) {
        return PerfilResponse.de(cargar(actual));
    }

    /**
     * Emite PerfilGuardado (E4) solo si algo cambió: un guardado sin cambios no debe hacer que
     * customer vuelva a procesar el enlace por NIT. El NIT de otra cuenta → 409 aquí, antes de
     * que exista un evento (README, "Carreras y su árbitro").
     */
    @Transactional
    public PerfilResponse actualizar(UsuarioActual actual, ActualizarPerfilRequest solicitud) {
        PerfilEntity perfil = cargar(actual);
        UsuarioEntity usuario = perfil.getUsuario();
        String nit = Nit.normalizar(solicitud.nit());
        if (!Nit.esConsumidorFinal(nit) && perfiles.existsByNitAndUsuarioIdNot(nit, usuario.getId())) {
            throw ErrorDeNegocio.conflicto("Ese NIT ya pertenece a otra cuenta");
        }
        if (perfiles.existsByDpiAndUsuarioIdNot(solicitud.dpi().strip(), usuario.getId())) {
            throw ErrorDeNegocio.conflicto("Ya existe una cuenta con ese DPI");
        }
        Instant ahora = Instant.now();
        // | y no ||: los dos cambios se tienen que aplicar.
        boolean cambio = usuario.cambiarNombre(solicitud.nombreCompleto(), ahora)
                | perfil.actualizar(nit, solicitud.dpi(), solicitud.telefono(), solicitud.direccion(), ahora);
        // Una cuenta pendiente no puede iniciar sesión, así que aquí siempre está confirmada;
        // la condición protege la regla "ningún E4 antes de confirmar".
        if (cambio && usuario.isConfirmado()) {
            long version = perfil.nuevaVersion(ahora);
            outbox.registrar(TiposEvento.PERFIL_GUARDADO, usuario.getId().toString(), version, evento(perfil));
        }
        return PerfilResponse.de(perfil);
    }

    public static PerfilGuardado evento(PerfilEntity perfil) {
        UsuarioEntity usuario = perfil.getUsuario();
        return new PerfilGuardado(usuario.getId(), perfil.getNit(), usuario.getNombreCompleto(), usuario.getCorreo(),
                perfil.getTelefono());
    }

    private PerfilEntity cargar(UsuarioActual actual) {
        return perfiles.conUsuario(actual.usuarioId())
                .orElseThrow(() -> ErrorDeNegocio.noEncontrado("No se encontró tu cuenta"));
    }
}
