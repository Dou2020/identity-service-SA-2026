-- identity_db: usuarios, perfil, sucursales y tarifas versionadas (DIAGRAMAS.md §7.2),
-- más la outbox y el inbox de mensajería.

CREATE TABLE sucursal (
    id           uuid          PRIMARY KEY,
    codigo       varchar(10)   NOT NULL UNIQUE,
    nombre       varchar(100)  NOT NULL UNIQUE,
    departamento varchar(50)   NOT NULL,
    municipio    varchar(50)   NOT NULL,
    direccion    varchar(200)  NOT NULL,
    telefono     varchar(15),
    latitud      numeric(9, 6) NOT NULL CHECK (latitud BETWEEN -90 AND 90),
    longitud     numeric(9, 6) NOT NULL CHECK (longitud BETWEEN -180 AND 180),
    activa       boolean       NOT NULL DEFAULT true,
    -- aggregateVersion de SucursalGuardada (E1)
    version      bigint        NOT NULL DEFAULT 0,
    creado_en    timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE usuario (
    id              uuid         PRIMARY KEY,
    correo          varchar(150) NOT NULL UNIQUE,
    -- Nunca NULL: una cuenta pendiente guarda un hash aleatorio inutilizable
    -- hasta que define su contraseña con el enlace.
    password_hash   varchar(100) NOT NULL,
    nombre_completo varchar(150) NOT NULL,
    rol             varchar(20)  NOT NULL
        CHECK (rol IN ('SYS_ADMIN', 'BRANCH_ADMIN', 'CASHIER', 'DRIVER', 'CUSTOMER')),
    sucursal_id     uuid         REFERENCES sucursal (id),
    -- Referencia por id a fleet.chofer: sin FK, es otra base.
    chofer_id       uuid,
    activo          boolean      NOT NULL DEFAULT true,
    -- NULL mientras la cuenta está pendiente (no ha definido su contraseña).
    confirmado_en   timestamptz,
    creado_en       timestamptz  NOT NULL DEFAULT now(),
    actualizado_en  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT usuario_sucursal_requerida
        CHECK (rol NOT IN ('BRANCH_ADMIN', 'CASHIER', 'DRIVER') OR sucursal_id IS NOT NULL),
    CONSTRAINT usuario_chofer_solo_driver
        CHECK (chofer_id IS NULL OR rol = 'DRIVER')
);

CREATE INDEX usuario_sucursal_rol_idx ON usuario (sucursal_id, rol) WHERE activo;

CREATE TABLE perfil (
    -- ON DELETE CASCADE solo para la purga de autorregistros sin confirmar a las 72 h;
    -- los usuarios confirmados no se borran, se desactivan.
    usuario_id     uuid         PRIMARY KEY REFERENCES usuario (id) ON DELETE CASCADE,
    -- Normalizado (mayúsculas, sin guiones ni espacios); 'CF' = consumidor final.
    nit            varchar(15)  NOT NULL DEFAULT 'CF',
    dpi            varchar(13)  NOT NULL UNIQUE,
    telefono       varchar(15)  NOT NULL,
    direccion      varchar(200) NOT NULL,
    -- aggregateVersion de PerfilGuardado (E4)
    version        bigint       NOT NULL DEFAULT 0,
    actualizado_en timestamptz  NOT NULL DEFAULT now()
);

-- Dos cuentas no pueden reclamar el mismo NIT; 'CF' lo pueden tener varias.
CREATE UNIQUE INDEX perfil_nit_uq ON perfil (nit) WHERE nit <> 'CF';

-- Nunca se actualiza: cada cambio inserta una versión con su vigente_desde.
CREATE TABLE tarifa (
    id             bigserial      PRIMARY KEY,
    tipo           varchar(20)    NOT NULL
        CHECK (tipo IN ('DEPRECIACION_KM', 'ALQUILER_KM', 'ALQUILER_DIA')),
    valor          numeric(12, 4) NOT NULL CHECK (valor >= 0),
    vigente_desde  date           NOT NULL,
    registrado_por uuid           NOT NULL REFERENCES usuario (id),
    registrado_en  timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT tarifa_tipo_fecha_uq UNIQUE (tipo, vigente_desde)
);

-- Outbox transaccional: el evento se inserta en la misma transacción que el
-- cambio y un relay lo publica (README, "Manejo de fallos").
CREATE TABLE outbox_event (
    id                bigserial   PRIMARY KEY,
    event_id          uuid        NOT NULL UNIQUE,
    tipo              varchar(80) NOT NULL,
    aggregate_id      varchar(64) NOT NULL,
    aggregate_version bigint      NOT NULL,
    -- El relay lo vacía al publicar E3 y E17, que llevan el token de contraseña.
    payload           jsonb       NOT NULL,
    correlation_id    varchar(64),
    ocurrido_en       timestamptz NOT NULL DEFAULT now(),
    publicado_en      timestamptz
);

CREATE INDEX outbox_event_pendiente_idx ON outbox_event (id) WHERE publicado_en IS NULL;

-- Inbox: un evento ya aplicado por este consumidor se descarta.
CREATE TABLE processed_event (
    event_id     uuid        NOT NULL,
    consumidor   varchar(60) NOT NULL,
    procesado_en timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumidor)
);
