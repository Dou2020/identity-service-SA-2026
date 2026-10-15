package gt.cunoc.sa.identity.api.sucursal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Datos de alta de sucursal; el administrador se asigna después desde Usuarios. */
public record CrearSucursalRequest(@NotNull @Valid DatosSucursalRequest sucursal) {}
