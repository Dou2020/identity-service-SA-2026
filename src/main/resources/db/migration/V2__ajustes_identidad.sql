-- Ajustes que aparecieron al implementar (DIAGRAMAS.md §7.2). Va en V2 y no editando V1
-- porque V1 ya está aplicada en los volúmenes de desarrollo.

-- Las cuentas que crea un admin, las DRIVER que nacen de ChoferGuardado (E5) y el admin
-- inicial no traen todos los datos: el perfil queda incompleto y el usuario lo completa en
-- PUT /api/perfil. El autorregistro sí los exige (validación del DTO). El UNIQUE de dpi
-- admite varios NULL.
ALTER TABLE perfil ALTER COLUMN dpi DROP NOT NULL;
ALTER TABLE perfil ALTER COLUMN telefono DROP NOT NULL;
ALTER TABLE perfil ALTER COLUMN direccion DROP NOT NULL;

-- aggregateVersion del último ChoferGuardado aplicado: uno más viejo que llegue tarde
-- se descarta (README, "Manejo de fallos").
ALTER TABLE usuario ADD COLUMN chofer_version bigint;

-- Un chofer, una cuenta: dos E5 del mismo chofer procesados a la vez no crean dos.
ALTER TABLE usuario ADD CONSTRAINT usuario_chofer_uq UNIQUE (chofer_id);
