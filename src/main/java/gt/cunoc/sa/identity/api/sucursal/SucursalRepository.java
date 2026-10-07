package gt.cunoc.sa.identity.api.sucursal;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import gt.cunoc.sa.identity.domain.SucursalEntity;

public interface SucursalRepository extends JpaRepository<SucursalEntity, UUID> {

    List<SucursalEntity> findAllByOrderByNombre();

    List<SucursalEntity> findByActivaOrderByNombre(boolean activa);
}
