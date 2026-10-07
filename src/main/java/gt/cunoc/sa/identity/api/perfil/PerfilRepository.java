package gt.cunoc.sa.identity.api.perfil;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import gt.cunoc.sa.identity.domain.PerfilEntity;

public interface PerfilRepository extends JpaRepository<PerfilEntity, UUID> {

    /** Perfil con su usuario en una sola consulta (open-in-view está apagado). */
    @Query("select p from PerfilEntity p join fetch p.usuario where p.usuarioId = :usuarioId")
    Optional<PerfilEntity> conUsuario(UUID usuarioId);

    boolean existsByNit(String nit);

    boolean existsByDpi(String dpi);

    boolean existsByNitAndUsuarioIdNot(String nit, UUID usuarioId);

    boolean existsByDpiAndUsuarioIdNot(String dpi, UUID usuarioId);
}
