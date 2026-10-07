# identity-service

Puerto `8081` · base `identity_db` · paquete `gt.cunoc.sa.identity`. El diseño (reglas, eventos,
Redis) está en el [README del proyecto](../README.md).

## Librerías

Spring Boot `3.5.16` sobre Java 21. Las versiones que el `pom.xml` no fija las pone el BOM de Boot;
aquí van resueltas (las mismas que empaqueta `target/app.jar`).

| Librería | Versión | Para qué |
|---|---|---|
| Spring Web MVC · Tomcat | 6.2.19 · 10.1.55 | API REST (`/api/**` y `/internal/**`) |
| Spring Data JPA · Hibernate ORM · HikariCP | 3.5.13 · 6.6.53.Final · 6.3.3 | Persistencia en `identity_db`, con `ddl-auto: validate` |
| Hibernate Validator (Bean Validation) | 8.0.3.Final | Validación de los `XRequest` |
| Spring Security | 6.5.11 | Autoriza con los `X-User-*` del gateway (`@PreAuthorize`); no valida el JWT |
| Spring Data Redis · Lettuce | 3.5.13 · 6.6.0.RELEASE | Escribe `auth:revocado`, `auth:valido-desde` y `auth:restablecer*` |
| Spring AMQP (`spring-rabbit`) · cliente AMQP | 3.2.12 · 5.25.0 | Publica E1–E4 y E17 por outbox; consume E5 (`ChoferGuardado`) |
| Flyway (`flyway-core` + `flyway-database-postgresql`) | 11.7.2 | Migraciones de `db/migration` |
| PostgreSQL JDBC | 42.7.11 | Driver de `identity_db` (PostgreSQL 16) |
| Spring Boot Actuator · Micrometer | 3.5.16 · 1.15.12 | `/actuator/health`, el healthcheck de Compose |
| springdoc-openapi (`webmvc-ui`) · Swagger UI | 2.8.17 · 5.32.2 | `/v3/api-docs` y Swagger UI; el gateway los agrega |
| Nimbus JOSE + JWT | 10.10 | Firma el JWT con HS256; el gateway lo verifica con la misma librería |

| Pruebas | Versión | Para qué |
|---|---|---|
| JUnit Jupiter · Mockito · AssertJ (`spring-boot-starter-test`) | 5.12.2 · 5.17.0 · 3.27.7 | Pruebas unitarias y de integración (`@SpringBootTest`) |
| Testcontainers (`junit-jupiter`, `postgresql`, `rabbitmq`) + `spring-boot-testcontainers` | 1.21.4 · 3.5.16 | PostgreSQL, RabbitMQ y Redis reales (`ContenedoresDePrueba`); Redis va en un `GenericContainer` |
| Awaitility | 4.2.2 | Esperar efectos asíncronos (relay, listener) sin `Thread.sleep` |
| Spring Security Test | 6.5.11 | Declarada, sin uso todavía: las pruebas mandan los `X-User-*` directo (`Escenario.como`) |

Imágenes: `maven:3.9-eclipse-temurin-21` compila y `eclipse-temurin:21-jre` corre (`Dockerfile`);
`postgres:16-alpine` para `identity-db`. Las pruebas usan `postgres:16-alpine`,
`rabbitmq:4-management-alpine` y `redis:7-alpine`.

## Estructura

```
identity-service/
├── pom.xml                                Boot 3.5.16, finalName app; web, JPA, Flyway, Security, Redis, AMQP, springdoc, Nimbus
├── Dockerfile                             dos etapas: Maven compila, JRE 21 corre target/app.jar
├── docker-compose.yml                     proyecto Compose propio: identity-db (red privada) + el servicio (también en transporte)
├── .dockerignore                          deja target/, .env y archivos del IDE fuera de la imagen
├── .gitignore                             lo que no se versiona: target/, .env (no .env.example), logs, IDE y SO
├── .env.example                           variables de su compose (su base, Redis, RabbitMQ, JWT_SECRET y el primer SYS_ADMIN); se copia a .env, que no se versiona
├── README.md                              estructura y endpoints (este archivo)
├── API.md                                 cada endpoint en detalle: entrada, salida, errores y efectos
└── src/
    ├── main/
    │   ├── java/gt/cunoc/sa/identity/
    │   │   ├── IdentityApplication.java   arranque; @EnableScheduling (relay y purga)
    │   │   ├── api/                       un paquete por recurso: controller, service (transacción y @PreAuthorize), repository y dto/
    │   │   │   ├── auth/
    │   │   │   │   ├── AutenticacionController.java         POST login, registro, recuperacion, contrasena y logout
    │   │   │   │   ├── AutenticacionService.java            login, registro, recuperación, contraseña y logout
    │   │   │   │   └── dto/
    │   │   │   │       ├── LoginRequest.java                {correo, contrasena}
    │   │   │   │       ├── LoginResponse.java               {token, expiraEn, usuario}
    │   │   │   │       ├── RegistroRequest.java             correo, nombre y perfil completo; sin contraseña
    │   │   │   │       ├── RegistroResponse.java            {usuarioId, mensaje}
    │   │   │   │       ├── RecuperacionRequest.java         {correo}
    │   │   │   │       ├── ContrasenaRequest.java           {token, nueva}: 8–72 caracteres, con letra y número
    │   │   │   │       └── MensajeResponse.java             {mensaje} que el frontend muestra tal cual
    │   │   │   ├── perfil/
    │   │   │   │   ├── PerfilController.java                GET y PUT /api/perfil
    │   │   │   │   ├── PerfilService.java                   perfil propio; E4 solo si algo cambió
    │   │   │   │   ├── PerfilRepository.java                perfil con su usuario; NIT y DPI ya tomados
    │   │   │   │   └── dto/
    │   │   │   │       ├── PerfilResponse.java              cuenta + perfil; completo = false si falta un dato
    │   │   │   │       └── ActualizarPerfilRequest.java     todo obligatorio salvo el NIT
    │   │   │   ├── usuario/
    │   │   │   │   ├── UsuarioController.java               lista, detalle, alta, cambio de rol y de estado
    │   │   │   │   ├── UsuarioService.java                  usuarios; invariantes con SELECT … FOR UPDATE
    │   │   │   │   ├── UsuarioRepository.java               búsquedas, candados de las invariantes y purga
    │   │   │   │   └── dto/
    │   │   │   │       ├── UsuarioResponse.java             cuenta; el perfil solo en el detalle
    │   │   │   │       ├── CrearUsuarioRequest.java         {correo, nombreCompleto, rol, sucursalId?} + perfil opcional
    │   │   │   │       ├── CambiarUsuarioRequest.java       {rol, sucursalId?}
    │   │   │   │       └── EstadoUsuarioRequest.java        {activo}
    │   │   │   ├── sucursal/
    │   │   │   │   ├── SucursalController.java              lista, detalle, alta, edición y estado
    │   │   │   │   ├── SucursalService.java                 sucursales (alta con su primer admin); E1
    │   │   │   │   ├── SucursalRepository.java              listas ordenadas por nombre
    │   │   │   │   └── dto/
    │   │   │   │       ├── SucursalResponse.java            datos, ubicación y estado
    │   │   │   │       ├── DatosSucursalRequest.java        datos y ubicación (latitud y longitud con 6 decimales)
    │   │   │   │       ├── CrearSucursalRequest.java        {sucursal, administrador}: la sucursal con su primer admin
    │   │   │   │       ├── SucursalCreadaResponse.java      {sucursal, administrador}
    │   │   │   │       └── EstadoSucursalRequest.java       {activa}
    │   │   │   ├── tarifa/
    │   │   │   │   ├── TarifaController.java                vigentes a una fecha, historial y versión nueva
    │   │   │   │   ├── TarifaService.java                   versiones de tarifa; E2
    │   │   │   │   ├── TarifaRepository.java                versión vigente a una fecha e historial
    │   │   │   │   └── dto/
    │   │   │   │       ├── TarifaResponse.java              {id, tipo, valor, vigenteDesde, registradoEn}
    │   │   │   │       └── CrearTarifaRequest.java          {tipo, valor, vigenteDesde?} (hoy por defecto)
    │   │   │   ├── interno/                                 /internal/**: solo red interna, el gateway no las expone
    │   │   │   │   ├── InternoController.java               S7 (admins de una sucursal) y reprocesar la DLQ
    │   │   │   │   ├── DirectorioService.java               S7: admins activos de una sucursal
    │   │   │   │   └── dto/
    │   │   │   │       └── AdministradorResponse.java       {usuarioId, nombreCompleto, correo}
    │   │   │   └── error/
    │   │   │       ├── ManejadorErrores.java                todo error → ProblemDetail; restricción única → 409
    │   │   │       ├── ErrorDeNegocio.java                  error esperado: 400, 401, 404, 409 o 422 con su detalle
    │   │   │       └── ServicioNoDisponibleException.java   Redis no respondió → 503
    │   │   ├── domain/                    entidades JPA y reglas
    │   │   │   ├── UsuarioEntity.java          cuenta pendiente o confirmada; rol, sucursal y chofer
    │   │   │   ├── PerfilEntity.java           NIT, DPI, teléfono y dirección; su versión es la de E4
    │   │   │   ├── SucursalEntity.java         datos, ubicación y estado; @Version es la versión de E1
    │   │   │   ├── TarifaEntity.java           una versión de tarifa; nunca se actualiza
    │   │   │   ├── Rol.java                    SYS_ADMIN, BRANCH_ADMIN, CASHIER, DRIVER, CUSTOMER
    │   │   │   ├── TipoTarifa.java             DEPRECIACION_KM, ALQUILER_KM, ALQUILER_DIA
    │   │   │   ├── Nit.java                    normalización y dígito verificador módulo 11
    │   │   │   ├── NitInvalidoException.java   → 422
    │   │   │   ├── Calendario.java             "hoy" en America/Guatemala, no en UTC
    │   │   │   └── ConIdAsignado.java          Persistable: persist() y no merge() con UUID asignado
    │   │   ├── application/               casos de uso compartidos entre recursos o sin endpoint; regla de alcance
    │   │   │   ├── AltaDeCuentas.java           cuenta pendiente + perfil + enlace de 72 h + E3
    │   │   │   ├── CuentasDeChofer.java         aplica ChoferGuardado (E5): crea o actualiza la cuenta DRIVER
    │   │   │   ├── PurgaRegistros.java          job horario: borra autorregistros sin confirmar a las 72 h
    │   │   │   ├── AdministradorInicial.java    crea el primer SYS_ADMIN al arrancar (ADMIN_CORREO)
    │   │   │   ├── Alcance.java                 un BRANCH_ADMIN solo toca su sucursal → 403
    │   │   │   ├── PropiedadesIdentidad.java    identity.*: vida de los enlaces y admin inicial
    │   │   │   └── package-info.java
    │   │   └── infrastructure/
    │   │       ├── messaging/                              outbox, inbox y RabbitMQ (se copia en cada servicio)
    │   │       │   ├── Sobre.java                          sobre de cada evento (eventId, type, aggregateVersion…)
    │   │       │   ├── Outbox.java                         registra el evento en la transacción del cambio
    │   │       │   ├── OutboxEvent.java                    fila de outbox_event
    │   │       │   ├── OutboxRepository.java               pendientes con FOR UPDATE SKIP LOCKED
    │   │       │   ├── RelayOutbox.java                    publica con confirms cada segundo; vacía E3 y E17
    │   │       │   ├── Inbox.java                          un evento ya aplicado no tiene efecto
    │   │       │   ├── ProcessedEvent.java                 fila de processed_event
    │   │       │   ├── ProcessedEventRepository.java       INSERT … ON CONFLICT DO NOTHING
    │   │       │   ├── ListenerEventos.java                consume identity.events y despacha por type
    │   │       │   ├── TopologiaRabbit.java                exchanges, cola, DLQ y alternate-exchange
    │   │       │   ├── ReintentosConfig.java               reintentos a 1, 2, 4 y 8 s; lo no reintentable va a la DLQ
    │   │       │   ├── EventoNoProcesableException.java    evento que nunca va a funcionar → DLQ
    │   │       │   ├── ReprocesadorDlq.java                devuelve la DLQ a la cola
    │   │       │   └── eventos/                            payload de cada evento: el contrato con los consumidores
    │   │       │       ├── TiposEvento.java                routing keys
    │   │       │       ├── SucursalGuardada.java           E1
    │   │       │       ├── ConfiguracionActualizada.java   E2
    │   │       │       ├── UsuarioCreado.java              E3 (lleva el token del enlace)
    │   │       │       ├── PerfilGuardado.java             E4
    │   │       │       ├── RecuperacionSolicitada.java     E17 (lleva el token del enlace)
    │   │       │       └── ChoferGuardado.java             E5, de fleet: los campos que identity lee
    │   │       ├── redis/
    │   │       │   ├── Sesiones.java                       auth:revocado (logout) y auth:valido-desde (corte)
    │   │       │   ├── Enlaces.java                        auth:restablecer* (Lua, GETDEL) y espera de recuperación
    │   │       │   └── package-info.java
    │   │       ├── security/
    │   │       │   ├── SeguridadConfig.java                cadena de Spring Security; 401 y 403 como ProblemDetail
    │   │       │   ├── EncabezadosGatewayFilter.java       X-User-* del gateway → UsuarioActual
    │   │       │   ├── UsuarioActual.java                  principal: usuario, sucursal, chofer y roles
    │   │       │   ├── EmisorJwt.java                      firma (HS256) y lee el JWT
    │   │       │   ├── PropiedadesJwt.java                 jwt.secreto y jwt.duracion
    │   │       │   └── ContrasenasConfig.java              BCrypt
    │   │       └── web/
    │   │           ├── CorrelationIdFilter.java            X-Correlation-Id → MDC y respuesta
    │   │           ├── DocumentacionConfig.java            OpenAPI: servidor relativo y bearerAuth
    │   │           └── WebConfig.java                      páginas como {content, page}
    │   └── resources/
    │       ├── application.yml                 puerto, base, Redis, RabbitMQ, JWT, enlaces y admin inicial
    │       └── db/migration/                   Flyway
    │           ├── V1__esquema_inicial.sql     tablas, restricciones, outbox e inbox
    │           └── V2__ajustes_identidad.sql   perfil incompleto, chofer_version, UQ (chofer_id)
    └── test/
        ├── java/gt/cunoc/sa/identity/
        │   ├── PruebaDeIntegracion.java        meta-anotación: un solo contexto para todas
        │   ├── ContenedoresDePrueba.java       PostgreSQL, Redis y RabbitMQ en Testcontainers
        │   ├── Escenario.java                  datos únicos por prueba y X-User-* puestos a mano
        │   ├── ColaDePrueba.java               cola temporal que ve lo que publica el relay
        │   ├── IdentityApplicationTests.java   migraciones aplicadas y admin inicial
        │   ├── SeguridadTest.java              401, 404, health y X-Correlation-Id
        │   ├── domain/
        │   │   ├── NitTest.java                normalización, dígito verificador, K y CF
        │   │   └── PerfilTest.java             la versión sube solo si algo cambió
        │   ├── infrastructure/security/
        │   │   └── EmisorJwtTest.java          claims, firma y secreto corto o no Base64
        │   └── application/                    integración (*IT)
        │       ├── AutenticacionIT.java        registro, enlace, login, recuperación y logout
        │       ├── UsuariosIT.java             invariantes, desactivación concurrente y autorización
        │       ├── SucursalesYTarifasIT.java   E1, E2, E3 y los 409
        │       ├── ChoferGuardadoIT.java       E5 duplicado, viejo y a la DLQ
        │       ├── PurgaRegistrosIT.java       autorregistros de más de 72 h
        │       └── RedisCaidoIT.java           503 sin Redis (contexto aparte)
        └── resources/config/
            └── application.yml   secreto JWT y admin inicial de prueba (se suma al principal)
```

## Endpoints

Resumen de una línea por endpoint. El detalle de cada uno (campos y validaciones, ejemplos, cada error
con su `detail` y los eventos que emite) está en [API.md](API.md).

Las rutas `/api/**` llegan por el gateway, que valida el JWT y agrega `X-User-*`. Todas las
respuestas de error son `ProblemDetail`; sin identidad → `401`, rol o sucursal ajenos → `403`.

### Autenticación — `/api/auth`

| Método | Ruta | Quién | Qué hace |
|---|---|---|---|
| POST | `/api/auth/login` | público | `{correo, contrasena}` → `200 {token, expiraEn, usuario}`. `401` si no coincide o la cuenta está inactiva o pendiente |
| POST | `/api/auth/registro` | público | Autorregistro de `CUSTOMER` **sin contraseña** (correo, nombre, NIT, DPI, teléfono, dirección; todo menos el NIT es obligatorio) → `201 {usuarioId, mensaje}`. `409` correo, NIT o DPI de otra cuenta; `422` NIT inválido. Emite E3 |
| POST | `/api/auth/recuperacion` | público | `{correo}` → **siempre `202`**, exista o no. Emite E17 si la cuenta existe y no está en espera |
| POST | `/api/auth/contrasena` | público | `{token, nueva}` (recuperación y bienvenida; 8–72 caracteres con letra y número) → `200`. `400` enlace inválido o vencido. El primer uso confirma la cuenta y emite E4. Cierra todas las sesiones |
| POST | `/api/auth/logout` | autenticado | Revoca el `jti` del token que reenvía el gateway → `204` |

### Perfil propio — `/api/perfil`

| Método | Ruta | Quién | Qué hace |
|---|---|---|---|
| GET | `/api/perfil` | autenticado | Cuenta (correo, rol, sucursal) y perfil; `completo: false` si falta DPI, teléfono o dirección |
| PUT | `/api/perfil` | autenticado | Nombre, NIT, DPI, teléfono y dirección (todos menos el NIT obligatorios) → `200`. `409` NIT o DPI de otra cuenta; `422` NIT inválido. Emite E4 solo si algo cambió |

### Usuarios — `/api/usuarios`

| Método | Ruta | Quién | Qué hace |
|---|---|---|---|
| GET | `/api/usuarios` | `SYS_ADMIN`; `BRANCH_ADMIN` (solo su sucursal) | Lista paginada (`page`, `size`, `sort`; `{content, page}`); filtros `rol`, `sucursalId`, `activo` |
| GET | `/api/usuarios/{id}` | `SYS_ADMIN`; `BRANCH_ADMIN` (solo su sucursal) | Detalle con perfil |
| POST | `/api/usuarios` | `SYS_ADMIN` crea `SYS_ADMIN` o `BRANCH_ADMIN`; `BRANCH_ADMIN` crea `CASHIER` de su sucursal | `{correo, nombreCompleto, rol, sucursalId?, nit?, dpi?, telefono?, direccion?}`: cuenta pendiente con enlace de 72 h → `201`. Emite E3. El `CASHIER` siempre queda en la sucursal del token. `DRIVER` nace de E5 y `CUSTOMER` de `/api/auth/registro` (`422`) |
| PATCH | `/api/usuarios/{id}` | `SYS_ADMIN` | `{rol, sucursalId?}`, solo entre `SYS_ADMIN`, `BRANCH_ADMIN` y `CASHIER` (`422` si no). Cierra sus sesiones. `409` si deja sin `SYS_ADMIN` activo o a una sucursal sin administrador |
| PATCH | `/api/usuarios/{id}/estado` | `SYS_ADMIN`; `BRANCH_ADMIN` (sus cajeros) | `{activo}`. Desactivar cierra sus sesiones; mismos `409`, y `409` para una cuenta `DRIVER` (la gobierna E5) |

### Sucursales — `/api/sucursales`

| Método | Ruta | Quién | Qué hace |
|---|---|---|---|
| GET | `/api/sucursales` | autenticado | Lista; filtro `activa` |
| GET | `/api/sucursales/{id}` | autenticado | Detalle con latitud y longitud |
| POST | `/api/sucursales` | `SYS_ADMIN` | `{sucursal: {codigo, nombre, departamento, municipio, direccion, telefono?, latitud, longitud}, administrador: {correo, nombreCompleto, telefono?}}`: sucursal **y su primer administrador** en una transacción → `201 {sucursal, administrador}`. `409` código, nombre o correo repetidos (no se crea nada). Emite E1 y E3 |
| PUT | `/api/sucursales/{id}` | `SYS_ADMIN` | Datos y ubicación. Emite E1 |
| PATCH | `/api/sucursales/{id}/estado` | `SYS_ADMIN` | `{activa}`. Emite E1 |

### Tarifas — `/api/tarifas`

| Método | Ruta | Quién | Qué hace |
|---|---|---|---|
| GET | `/api/tarifas/vigentes?fecha=` | `SYS_ADMIN`, `BRANCH_ADMIN` | Versión vigente de cada tipo a la fecha (hoy por defecto) |
| GET | `/api/tarifas?tipo=` | `SYS_ADMIN` | Historial de versiones |
| POST | `/api/tarifas` | `SYS_ADMIN` | `{tipo, valor, vigenteDesde}` (hoy por defecto) agrega una versión → `201`. `409` si ya hay una de ese tipo en esa fecha. Emite E2 |

### Internas — `/internal` (red interna, sin gateway)

| Método | Ruta | Quién | Qué hace |
|---|---|---|---|
| GET | `/internal/sucursales/{id}/administradores` | notification-service (S7) | Nombre y correo de los `BRANCH_ADMIN` activos de la sucursal |
| POST | `/internal/dlq/reprocesar` | operador | Reencola los mensajes de `identity.events.dlq` → `{reprocesados}` |

### Técnicas

| Método | Ruta | Qué es |
|---|---|---|
| GET | `/actuator/health` | Salud (healthcheck de Compose) |
| GET | `/v3/api-docs` · `/swagger-ui.html` | OpenAPI y Swagger UI |
