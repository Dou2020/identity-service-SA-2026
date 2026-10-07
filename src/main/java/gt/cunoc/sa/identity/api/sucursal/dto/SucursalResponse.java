package gt.cunoc.sa.identity.api.sucursal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import gt.cunoc.sa.identity.domain.SucursalEntity;

public record SucursalResponse(
        UUID id,
        String codigo,
        String nombre,
        String departamento,
        String municipio,
        String direccion,
        String telefono,
        BigDecimal latitud,
        BigDecimal longitud,
        boolean activa,
        Instant creadoEn) {

    public static SucursalResponse de(SucursalEntity s) {
        return new SucursalResponse(s.getId(), s.getCodigo(), s.getNombre(), s.getDepartamento(), s.getMunicipio(),
                s.getDireccion(), s.getTelefono(), s.getLatitud(), s.getLongitud(), s.isActiva(), s.getCreadoEn());
    }
}
