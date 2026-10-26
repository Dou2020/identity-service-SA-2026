-- Los ids de fleet, trip y sales se guardan como texto (decisión del 2026-10-25): fleet usa
-- bigserial y publica ChoferGuardado (E5) con su id numérico ("3"), que en una columna uuid no
-- entra y mandaría el evento a la DLQ. varchar y no bigint: así no dependemos de cómo numere
-- fleet, y los choferes que ya se guardaron con UUID siguen valiendo tal cual.
--
-- ALTER COLUMN TYPE reconstruye el índice de usuario_chofer_uq y conserva el CHECK
-- usuario_chofer_solo_driver: ninguno depende del tipo. El USING deja los UUID en su forma
-- canónica (minúsculas con guiones), la misma que antes salía en el JWT.
ALTER TABLE usuario ALTER COLUMN chofer_id TYPE varchar(64) USING chofer_id::text;
