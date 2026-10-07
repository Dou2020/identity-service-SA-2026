package gt.cunoc.sa.identity.api.tarifa;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import gt.cunoc.sa.identity.domain.TarifaEntity;
import gt.cunoc.sa.identity.domain.TipoTarifa;

public interface TarifaRepository extends JpaRepository<TarifaEntity, Long> {

    /** La versión vigente a una fecha: la más reciente que ya empezó. */
    Optional<TarifaEntity> findFirstByTipoAndVigenteDesdeLessThanEqualOrderByVigenteDesdeDesc(TipoTarifa tipo,
            LocalDate fecha);

    List<TarifaEntity> findByTipoOrderByVigenteDesdeDesc(TipoTarifa tipo);

    List<TarifaEntity> findAllByOrderByTipoAscVigenteDesdeDesc();
}
