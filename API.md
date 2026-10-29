# identity-service — API

Referencia de cada endpoint: qué entra, qué sale, qué errores da (con el `detail` exacto que muestra el
frontend) y qué efectos tiene. El resumen de una línea por endpoint está en el [README](README.md#endpoints);
las reglas de negocio, los eventos y Redis, en el [README del proyecto](../README.md).

| | URL base |
|---|---|
| Por el gateway (frontend) | `http://localhost:8080` — valida el JWT y aplica los límites |
| Directo al servicio (solo desarrollo) | `http://localhost:8081` — los `X-User-*` se ponen a mano |
| Entre servicios (red `transporte`) | `http://identity-service:8081` — solo `/internal/**` |
| Swagger | `http://localhost:8080/swagger-ui.html` (agregado) · `http://localhost:8081/swagger-ui.html` |

## Contenido

- [Convenciones](#convenciones)
  - [Autenticación](#autenticación) · [Formatos](#formatos) · [Paginación](#paginación) ·
    [Errores](#errores) · [Respuestas del gateway y límites](#respuestas-del-gateway-y-límites) ·
    [Reglas de los campos](#reglas-de-los-campos)
- [Modelos de respuesta](#modelos-de-respuesta)
- [Autenticación — `/api/auth`](#autenticación--apiauth): [login](#post-apiauthlogin) ·
  [registro](#post-apiauthregistro) · [recuperacion](#post-apiauthrecuperacion) ·
  [contrasena](#post-apiauthcontrasena) · [logout](#post-apiauthlogout)
- [Perfil propio — `/api/perfil`](#perfil-propio--apiperfil): [GET](#get-apiperfil) · [PUT](#put-apiperfil)
- [Usuarios — `/api/usuarios`](#usuarios--apiusuarios): [lista](#get-apiusuarios) ·
  [detalle](#get-apiusuariosid) · [alta](#post-apiusuarios) · [rol](#patch-apiusuariosid) ·
  [estado](#patch-apiusuariosidestado)
- [Sucursales — `/api/sucursales`](#sucursales--apisucursales): [lista](#get-apisucursales) ·
  [detalle](#get-apisucursalesid) · [alta](#post-apisucursales) · [edición](#put-apisucursalesid) ·
  [estado](#patch-apisucursalesidestado)
- [Tarifas — `/api/tarifas`](#tarifas--apitarifas): [vigentes](#get-apitarifasvigentes) ·
  [historial](#get-apitarifas) · [nueva versión](#post-apitarifas)
- [Internas — `/internal`](#internas--internal): [administradores (S7)](#get-internalsucursalesidadministradores) ·
  [nombre de la sucursal](#get-internalsucursalesid) · [reprocesar DLQ](#post-internaldlqreprocesar)
- [Eventos que consume](#eventos-que-consume): [E5 `ChoferGuardado`](#e5-choferguardado)
- [Técnicas](#técnicas)

---

## Convenciones

### Autenticación

`POST /api/auth/login` devuelve un JWT (HS256, 8 h). Todo `/api/**` salvo las cuatro rutas públicas de
`/api/auth` exige `Authorization: Bearer <token>`. El gateway verifica firma, vencimiento y revocación,
**borra** cualquier `X-User-*` que mande el cliente y reenvía los del token:

| Encabezado | Claim del JWT | Valor |
|---|---|---|
| `X-User-Id` | `sub` | UUID del usuario |
| `X-User-Roles` | `roles` | el rol (`SYS_ADMIN`, `BRANCH_ADMIN`, `CASHIER`, `DRIVER` o `CUSTOMER`) |
| `X-Sucursal-Id` | `sucursalId` | solo `BRANCH_ADMIN` y `CASHIER`: su sucursal, que es su alcance |
| `X-Chofer-Id` | `choferId` | solo `DRIVER`: su id en fleet-service, como texto (hoy numérico, `3`) |

El token además lleva `jti`, `iat` y `exp` (en segundos). identity no vuelve a validar el JWT (salvo en
el logout): autoriza con esos encabezados. Por eso, llamando directo al puerto 8081 en desarrollo, se
ponen a mano:

```bash
curl -H 'X-User-Id: 0d3f5a1e-6c2b-4f7e-9a8d-1b2c3d4e5f60' -H 'X-User-Roles: SYS_ADMIN' \
     http://localhost:8081/api/usuarios
```

Cada respuesta trae `X-Correlation-Id`: el que mandó el cliente o el gateway si cumple
`[A-Za-z0-9._-]{1,64}`, o uno nuevo. Aparece en los logs y en el sobre de los eventos que provoca la
request.

### Formatos

- JSON en UTF-8. Los ids son UUID, salvo el de las tarifas, que es numérico, y los de fleet, trip y sales,
  que son **texto**: su id tal cual, hoy numérico. En identity solo aparece el `choferId` (p. ej. `"3"`),
  siempre como string JSON. Los choferes guardados antes con UUID lo conservan.
- Instantes (`creadoEn`, `expiraEn`, `registradoEn`): ISO-8601 en UTC, p. ej. `2026-10-06T23:44:43.636785Z`.
- Fechas de negocio (`fecha`, `vigenteDesde`): `AAAA-MM-DD`. "Hoy" es el día en `America/Guatemala`.
- Montos y coordenadas: número JSON (`14.834999`), no texto.
- Enumerados: `Rol` = `SYS_ADMIN` · `BRANCH_ADMIN` · `CASHIER` · `DRIVER` · `CUSTOMER`;
  `TipoTarifa` = `DEPRECIACION_KM` · `ALQUILER_KM` · `ALQUILER_DIA`. Otro valor → `400`.
- `null`: `UsuarioResponse` **omite** los campos nulos (`sucursalId`, `choferId`, `perfil`). Dentro de su
  `perfil` y en `PerfilResponse` los nulos sí aparecen como `null`.

### Paginación

Solo `GET /api/usuarios` pagina. Parámetros de query:

| Parámetro | Por defecto | Notas |
|---|---|---|
| `page` | `0` | empieza en 0 |
| `size` | `20` | |
| `sort` | `nombreCompleto,asc` | `campo,asc\|desc`, repetible. Campos útiles: `nombreCompleto`, `correo`, `rol`, `activo`, `creadoEn`. Uno inexistente → `400 No se puede ordenar por '<campo>'` |

```json
{
  "content": [ { "id": "…", "correo": "…" } ],
  "page": { "size": 20, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

### Errores

Todo error sale como `ProblemDetail` (RFC 9457), con `Content-Type: application/problem+json`. El
frontend muestra `detail`:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Ya existe una cuenta con ese correo",
  "instance": "/api/usuarios"
}
```

Un body que no pasa la validación responde `400` con un mapa `errores` (campo → mensaje, en español; los
campos anidados van con punto):

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Hay datos inválidos en la solicitud",
  "instance": "/api/sucursales",
  "errores": {
    "sucursal.codigo": "solo letras, números y guiones",
    "sucursal.latitud": "debe ser menor que o igual a 90",
    "administrador.correo": "debe ser una dirección de correo electrónico con formato correcto"
  }
}
```

Errores que puede dar cualquier endpoint. En cada endpoint solo se listan los propios.

| Estado | `detail` | Cuándo |
|---|---|---|
| `400` | `Hay datos inválidos en la solicitud` + `errores` | El body no pasa Bean Validation |
| `400` | `Failed to read request` | JSON mal formado, o un valor de tipo equivocado en el body (p. ej. un enum inexistente). Lo genera Spring y sale en inglés |
| `400` | `Failed to convert '<parámetro>' with value: '<valor>'` | UUID, enum, fecha o booleano inválido en la ruta o el query. En inglés, como el anterior |
| `401` | `Inicia sesión para continuar` | Ruta protegida sin `X-User-Id` (solo llamando directo; por el gateway lo corta antes el gateway) |
| `403` | `No tienes permiso para esta acción` | El rol no alcanza, o el recurso es de otra sucursal |
| `404` | `No static resource <ruta>.` | La ruta no existe |
| `405` | `Method '<MÉTODO>' is not supported.` | Método no soportado; el encabezado `Allow` dice cuáles sí |
| `409` | ver la tabla siguiente | Choque con un dato único o con otra edición |
| `503` | `El servicio no está disponible en este momento. Intenta de nuevo en unos minutos` | Redis no respondió en una operación que lo necesita (enlaces y sesiones). La transacción se revierte: no se guarda nada |
| `500` | `Ocurrió un error inesperado. Intenta de nuevo más tarde` | Error no controlado (queda en el log con su correlation id) |

Los `409` por datos repetidos. El caso de uso los detecta antes de escribir; si dos requests simultáneas
pasan esa revisión, la restricción única de PostgreSQL decide y la que pierde recibe el mismo mensaje:

| `detail` | Dato |
|---|---|
| `Ya existe una cuenta con ese correo` | correo de usuario |
| `Ese NIT ya pertenece a otra cuenta` | NIT del perfil (salvo `CF`, que pueden tener varias cuentas) |
| `Ya existe una cuenta con ese DPI` | DPI del perfil |
| `Ya existe una sucursal con ese código` | código de sucursal |
| `Ya existe una sucursal con ese nombre` | nombre de sucursal |
| `Ya hay una versión de esa tarifa con esa fecha de vigencia` | tipo + `vigenteDesde` de una tarifa |
| `Alguien más modificó estos datos mientras tanto. Vuelve a cargarlos e intenta de nuevo` | dos ediciones simultáneas de la misma sucursal (`@Version`) |
| `Otra operación está modificando estos datos. Intenta de nuevo` | no se obtuvo un bloqueo de PostgreSQL |
| `El cambio choca con datos que ya existen` | otra restricción sin mensaje propio |

### Respuestas del gateway y límites

Por el gateway pueden llegar estas respuestas sin que la request toque identity:

| Estado | `detail` | Cuándo |
|---|---|---|
| `401` | `Inicia sesión para continuar` | Ruta protegida sin `Authorization` (con `WWW-Authenticate: Bearer`) |
| `401` | `Tu sesión no es válida o venció. Inicia sesión de nuevo` | Firma, formato o vencimiento inválidos |
| `401` | `Tu sesión se cerró. Inicia sesión de nuevo` | Token revocado por logout, o emitido antes de un corte de sesiones (contraseña nueva, cambio de rol, desactivación) |
| `429` | `Demasiados intentos, espera un momento` | Se agotó el límite de la ruta |
| `503` | `El servicio no está disponible en este momento. Intenta de nuevo en unos minutos` | identity no está arriba |
| `504` | `El servicio tardó demasiado en responder. Intenta de nuevo` | identity no respondió en 15 s |

| Rutas | Clave | Límite |
|---|---|---|
| `POST /api/auth/login`, `/registro`, `/recuperacion`, `/contrasena` | IP | 5 por minuto |
| Las demás: `/api/auth/logout`, `/api/perfil`, `/api/usuarios/**`, `/api/sucursales/**`, `/api/tarifas/**` | usuario del token | 20 por segundo, ráfagas de 40 |

Las respuestas traen `X-RateLimit-Remaining`, `X-RateLimit-Replenish-Rate`, `X-RateLimit-Burst-Capacity`
y `X-RateLimit-Requested-Tokens`. En las rutas públicas cada request gasta 60 de 300 fichas, así que
`Remaining / 60` es la cantidad de intentos que quedan. Si Redis cae, el gateway deja pasar sin límite.
`/internal/**` no tiene ruta en el gateway.

### Reglas de los campos

| Campo | Regla | Mensaje si no la cumple |
|---|---|---|
| `correo` | Formato de correo, hasta 150. Se guarda sin espacios y en minúsculas, y login y recuperación lo normalizan igual, así que no distingue mayúsculas | `debe ser una dirección de correo electrónico con formato correcto` |
| `nit` | Opcional, hasta 20 al enviarlo. Se le quitan guiones y espacios y se pasa a mayúsculas. Vacío, ausente o `CF` = consumidor final (`CF`). Si no, de 1 a 14 dígitos y un último carácter dígito o `K`; el dígito verificador (módulo 11) no se valida. Ejemplo: `1234567-9`, que se guarda como `12345679` | `422 El NIT solo puede tener dígitos y terminar en un dígito o K` |
| `dpi` | Exactamente 13 dígitos | `debe tener 13 dígitos` |
| `telefono` | 8 a 15 caracteres entre dígitos, `+`, espacio y `-` | `no es un teléfono válido` |
| `nombreCompleto` | Hasta 150; se guarda sin espacios al borde | `no debe estar vacío` |
| `direccion` | Hasta 200 | |
| `nueva` (contraseña) | 8 a 72 caracteres, al menos una letra y un número, y no más de 72 bytes en UTF-8 (límite de BCrypt) | `el tamaño debe estar entre 8 y 72` · `debe tener al menos una letra y un número` · `400 La contraseña es demasiado larga` |

Los obligatorios vacíos dan `no debe estar vacío` (texto) o `no debe ser nulo` (otros tipos).

---

## Modelos de respuesta

### `UsuarioResponse`

Una cuenta. Los campos `null` no se envían.

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | UUID | |
| `correo` | string | en minúsculas |
| `nombreCompleto` | string | |
| `rol` | `Rol` | |
| `sucursalId` | UUID | solo `BRANCH_ADMIN`, `CASHIER` y `DRIVER` |
| `choferId` | string | solo `DRIVER`: su id en fleet-service, como texto (hoy numérico, `"3"`) |
| `activo` | boolean | `false` = desactivada: no puede iniciar sesión |
| `confirmado` | boolean | `false` mientras no define su contraseña con el enlace del correo |
| `creadoEn` | instante | |
| `perfil` | `PerfilResumen` | solo en el detalle y en las altas; las listas y el login no lo traen |

`PerfilResumen`: `nit` (string, `CF` si no tiene), `dpi`, `telefono`, `direccion` (string o `null`) y
`completo` (boolean: `true` si tiene DPI, teléfono y dirección).

```json
{
  "id": "16fd2706-8baf-433b-82eb-8c7fada847da",
  "correo": "cajero.xela@transporte.gt",
  "nombreCompleto": "Ana López",
  "rol": "CASHIER",
  "sucursalId": "1b4e28ba-2fa1-41d2-883f-0016d3cca427",
  "activo": true,
  "confirmado": false,
  "creadoEn": "2026-10-07T15:02:11.482113Z",
  "perfil": { "nit": "CF", "dpi": null, "telefono": null, "direccion": null, "completo": false }
}
```

### `PerfilResponse`

La cuenta y el perfil del usuario que hace la request. Todos los campos se envían, también los `null`.

| Campo | Tipo | Descripción |
|---|---|---|
| `usuarioId` | UUID | |
| `correo` | string | |
| `nombreCompleto` | string | |
| `rol` | `Rol` | |
| `sucursalId` | UUID o `null` | |
| `choferId` | string o `null` | solo `DRIVER`: su id en fleet-service, como texto |
| `nit` | string | `CF` si no tiene |
| `dpi` | string o `null` | |
| `telefono` | string o `null` | |
| `direccion` | string o `null` | |
| `completo` | boolean | `false` si falta DPI, teléfono o dirección: el frontend pide completarlo |

### `SucursalResponse`

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | UUID | |
| `codigo` | string | en mayúsculas |
| `nombre` | string | |
| `departamento` | string | |
| `municipio` | string | |
| `direccion` | string | |
| `telefono` | string o `null` | |
| `latitud` | número | hasta 6 decimales |
| `longitud` | número | hasta 6 decimales |
| `activa` | boolean | |
| `creadoEn` | instante | |

```json
{
  "id": "1b4e28ba-2fa1-41d2-883f-0016d3cca427",
  "codigo": "XELA-01",
  "nombre": "Quetzaltenango Centro",
  "departamento": "Quetzaltenango",
  "municipio": "Quetzaltenango",
  "direccion": "4a. calle 12-35, zona 1",
  "telefono": "7765-4321",
  "latitud": 14.834999,
  "longitud": -91.518757,
  "activa": true,
  "creadoEn": "2026-10-07T14:58:02.117450Z"
}
```

### `TarifaResponse`

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | entero | crece con cada versión |
| `tipo` | `TipoTarifa` | `DEPRECIACION_KM` (costo de depreciación por km, lo usa finance), `ALQUILER_KM` y `ALQUILER_DIA` (precio del alquiler por km y por día, lo usa customer) |
| `valor` | número | hasta 4 decimales |
| `vigenteDesde` | fecha | desde qué día rige |
| `registradoEn` | instante | cuándo se guardó |

```json
{ "id": 7, "tipo": "DEPRECIACION_KM", "valor": 1.2500, "vigenteDesde": "2026-10-01", "registradoEn": "2026-10-07T15:10:44.902331Z" }
```

### Otros

| Modelo | Forma |
|---|---|
| `LoginResponse` | `{token, expiraEn, usuario: UsuarioResponse sin perfil}` |
| `RegistroResponse` | `{usuarioId, mensaje}` |
| `SucursalCreadaResponse` | `{sucursal: SucursalResponse, administrador: UsuarioResponse con perfil}` |
| `MensajeResponse` | `{mensaje}`, texto para mostrar tal cual |
| `AdministradorResponse` | `{usuarioId, nombreCompleto, correo}` |
| `SucursalNombreResponse` | `{nombre}` |

---

## Autenticación — `/api/auth`

### `POST /api/auth/login`

Inicia sesión y devuelve un JWT de 8 h.

**Acceso:** público · 5 por minuto por IP.

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `correo` | string | sí | formato de correo; no distingue mayúsculas |
| `contrasena` | string | sí | |

```json
{ "correo": "admin@transporte.gt", "contrasena": "Transporte2026" }
```

**`200`** — `LoginResponse`:

```json
{
  "token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOi…",
  "expiraEn": "2026-10-07T23:02:11Z",
  "usuario": {
    "id": "0d3f5a1e-6c2b-4f7e-9a8d-1b2c3d4e5f60",
    "correo": "admin@transporte.gt",
    "nombreCompleto": "Administrador del sistema",
    "rol": "SYS_ADMIN",
    "activo": true,
    "confirmado": true,
    "creadoEn": "2026-10-06T23:44:43.636785Z"
  }
}
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `401` | `Correo o contraseña incorrectos` | El correo no existe, la contraseña no coincide, la cuenta está desactivada o todavía no está confirmada. Es la misma respuesta, y tarda lo mismo, en los cuatro casos, para no revelar qué correos están registrados |

**Efectos:** ninguno. El token no se guarda en ningún lado; el frontend lo envía en cada request.

### `POST /api/auth/registro`

Autorregistro de un cliente (`CUSTOMER`) **sin contraseña**: la cuenta queda pendiente y recibe por
correo un enlace de 72 h para definirla.

**Acceso:** público · 5 por minuto por IP.

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `correo` | string | sí | formato de correo, hasta 150 |
| `nombreCompleto` | string | sí | hasta 150 |
| `nit` | string | no | regla de NIT; vacío o ausente = `CF` |
| `dpi` | string | sí | 13 dígitos |
| `telefono` | string | sí | regla de teléfono |
| `direccion` | string | sí | hasta 200 |

```json
{
  "correo": "maria.perez@gmail.com",
  "nombreCompleto": "María Pérez",
  "nit": "1234567-9",
  "dpi": "2456789012301",
  "telefono": "5512-3456",
  "direccion": "6a. avenida 3-20, zona 3, Quetzaltenango"
}
```

**`201`** — `RegistroResponse` (sin `Location`):

```json
{ "usuarioId": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "mensaje": "Te enviamos un correo con el enlace para definir tu contraseña" }
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `409` | `Ya existe una cuenta con ese correo` | |
| `409` | `Ese NIT ya pertenece a otra cuenta` | Nunca con `CF` |
| `409` | `Ya existe una cuenta con ese DPI` | |
| `422` | el mensaje de formato del NIT | Ver [reglas de los campos](#reglas-de-los-campos) |
| `503` | el genérico | Redis no pudo guardar el enlace; la cuenta no se crea |

**Efectos:**

- Crea la cuenta pendiente (no puede iniciar sesión) con el perfil completo.
- Guarda en Redis el enlace de bienvenida, que vence en 72 h.
- Emite E3 `identity.usuario.creado`, con el que notification envía el correo.
- Todavía no emite E4: customer se entera del cliente cuando confirma.
- Si no confirma en 72 h, la purga horaria borra la cuenta y su correo, NIT y DPI quedan libres.

### `POST /api/auth/recuperacion`

Pide un enlace para definir una contraseña nueva. Sirve también a una cuenta pendiente cuyo enlace de
bienvenida venció.

**Acceso:** público · 5 por minuto por IP.

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `correo` | string | sí | formato de correo |

```json
{ "correo": "maria.perez@gmail.com" }
```

**`202`** — `MensajeResponse`, **siempre igual**, exista o no la cuenta:

```json
{ "mensaje": "Si el correo está registrado, recibirás un enlace para definir tu contraseña" }
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `503` | el genérico | Redis no respondió. Pasa con cualquier correo, exista o no, así que tampoco revela nada |

**Efectos:**

- Como máximo un correo por minuto a cada dirección. Una segunda solicitud dentro de ese minuto responde
  el mismo `202` y no envía nada.
- Si la cuenta existe y está activa, emite un enlace de 30 min, que invalida el enlace anterior (también
  el de bienvenida), y E17 `identity.recuperacion.solicitada`, con el que notification envía el correo.

### `POST /api/auth/contrasena`

Define la contraseña con el token del enlace. Es el mismo endpoint para la bienvenida y para la
recuperación.

**Acceso:** público · 5 por minuto por IP.

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `token` | string | sí | el token del enlace del correo |
| `nueva` | string | sí | regla de contraseña |

```json
{ "token": "Qm9yZGVyQ29sbGllLUJlc3REb2ctMjAyNi0xMC0wNw", "nueva": "Transporte2026" }
```

**`200`** — `MensajeResponse`:

```json
{ "mensaje": "Tu contraseña quedó guardada. Ya puedes iniciar sesión" }
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `400` | `Hay datos inválidos en la solicitud` + `errores.nueva` | La contraseña no cumple la regla. **No** gasta el enlace |
| `400` | `La contraseña es demasiado larga` | Más de 72 bytes en UTF-8 (posible con muchas tildes). Tampoco gasta el enlace |
| `400` | `El enlace no es válido o ya venció. Solicita uno nuevo` | Token mal formado, inexistente, vencido o ya usado, o cuenta desactivada |
| `503` | el genérico | Redis no respondió |

**Efectos:**

- Consume el token: es de un solo uso, y de dos clics simultáneos solo uno lo logra.
- Guarda la contraseña (BCrypt).
- El primer uso confirma la cuenta y emite E4 `identity.perfil.guardado`, con el que customer crea o
  enlaza al cliente.
- Cierra **todas** las sesiones abiertas del usuario: el gateway rechaza los tokens emitidos antes.

### `POST /api/auth/logout`

Cierra la sesión actual: revoca el token con el que se hace la request.

**Acceso:** cualquier usuario autenticado · 20 por segundo por usuario.

**Entrada:** solo el encabezado `Authorization: Bearer <token>` (el mismo que el gateway ya validó). Sin
body.

**`204`** sin cuerpo.

| Estado | `detail` | Cuándo |
|---|---|---|
| `401` | `La sesión no es válida` | El token no tiene una firma válida de identity o es de otro usuario que el de `X-User-Id`. Solo puede pasar llamando directo al 8081 |
| `400` | `Required header 'Authorization' is not present.` | Llamando directo sin el encabezado |
| `503` | el genérico | Redis no respondió; la sesión sigue abierta |

**Efectos:** guarda `auth:revocado:<jti>` en Redis hasta que el token vence. Solo cierra esta sesión;
las de otros dispositivos siguen abiertas.

---

## Perfil propio — `/api/perfil`

Cada usuario solo ve y edita el suyo: sale de `X-User-Id`, no de la ruta.

### `GET /api/perfil`

**Acceso:** cualquier usuario autenticado.

**`200`** — `PerfilResponse`:

```json
{
  "usuarioId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "correo": "maria.perez@gmail.com",
  "nombreCompleto": "María Pérez",
  "rol": "CUSTOMER",
  "sucursalId": null,
  "choferId": null,
  "nit": "12345679",
  "dpi": "2456789012301",
  "telefono": "5512-3456",
  "direccion": "6a. avenida 3-20, zona 3, Quetzaltenango",
  "completo": true
}
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No se encontró tu cuenta` | El `X-User-Id` no corresponde a ninguna cuenta |

### `PUT /api/perfil`

Guarda el perfil propio. Reemplaza todos los campos: así una cuenta creada por un admin completa su
perfil, y ninguna puede dejarlo incompleto después.

**Acceso:** cualquier usuario autenticado.

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `nombreCompleto` | string | sí | hasta 150 |
| `nit` | string | no | regla de NIT. **Si no se envía, el NIT queda en `CF`** |
| `dpi` | string | sí | 13 dígitos |
| `telefono` | string | sí | regla de teléfono |
| `direccion` | string | sí | hasta 200 |

```json
{
  "nombreCompleto": "María Pérez de León",
  "nit": "1234567-9",
  "dpi": "2456789012301",
  "telefono": "5512-3456",
  "direccion": "6a. avenida 3-20, zona 3, Quetzaltenango"
}
```

**`200`** — `PerfilResponse` ya actualizado.

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No se encontró tu cuenta` | |
| `409` | `Ese NIT ya pertenece a otra cuenta` | Nunca con `CF` |
| `409` | `Ya existe una cuenta con ese DPI` | |
| `422` | el mensaje de formato del NIT | |

**Efectos:** si algo cambió, sube la versión del perfil y emite E4 `identity.perfil.guardado` (customer
actualiza el cliente o lo enlaza por NIT). Un guardado sin cambios no emite nada. No cierra sesiones.

---

## Usuarios — `/api/usuarios`

Los usuarios no se eliminan, solo se desactivan. `DRIVER` nace de fleet-service (evento E5) y `CUSTOMER`
del autorregistro; aquí se administra el personal.

### `GET /api/usuarios`

Lista paginada de cuentas, sin perfil.

**Acceso:** `SYS_ADMIN` ve todas. `BRANCH_ADMIN` solo las de su sucursal (sus admins, cajeros y
choferes); para él, `sucursalId` del query se ignora y se usa la del token.

| Query | Tipo | Descripción |
|---|---|---|
| `rol` | `Rol` | filtra por rol |
| `sucursalId` | UUID | filtra por sucursal (solo `SYS_ADMIN`) |
| `activo` | boolean | filtra por estado |
| `page`, `size`, `sort` | | ver [paginación](#paginación) |

```
GET /api/usuarios?rol=CASHIER&activo=true&size=10&sort=creadoEn,desc
```

**`200`** — página de `UsuarioResponse` sin `perfil`:

```json
{
  "content": [
    {
      "id": "16fd2706-8baf-433b-82eb-8c7fada847da",
      "correo": "cajero.xela@transporte.gt",
      "nombreCompleto": "Ana López",
      "rol": "CASHIER",
      "sucursalId": "1b4e28ba-2fa1-41d2-883f-0016d3cca427",
      "activo": true,
      "confirmado": true,
      "creadoEn": "2026-10-07T15:02:11.482113Z"
    }
  ],
  "page": { "size": 10, "number": 0, "totalElements": 1, "totalPages": 1 }
}
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `403` | `No tienes permiso para esta acción` | Otro rol, o un `BRANCH_ADMIN` sin `X-Sucursal-Id` |

### `GET /api/usuarios/{id}`

Detalle de una cuenta, con su perfil.

**Acceso:** `SYS_ADMIN`; `BRANCH_ADMIN` solo de su sucursal.

**`200`** — `UsuarioResponse` con `perfil` (ejemplo en [modelos](#usuarioresponse)).

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No existe ese usuario` | Se revisa antes que el alcance |
| `403` | `No tienes permiso para esta acción` | Otro rol, o un `BRANCH_ADMIN` pide una cuenta de otra sucursal o sin sucursal (`SYS_ADMIN`, `CUSTOMER`) |

### `POST /api/usuarios`

Crea una cuenta de personal. Nace pendiente: el empleado recibe por correo un enlace de 72 h para
definir su contraseña y completa su perfil después.

**Acceso:**

| Quién crea | Rol que puede crear | Sucursal |
|---|---|---|
| `SYS_ADMIN` | `SYS_ADMIN` | ninguna; `sucursalId` se ignora |
| `SYS_ADMIN` | `BRANCH_ADMIN` | `sucursalId` obligatoria, existente y activa |
| `BRANCH_ADMIN` | `CASHIER` | la del token; `sucursalId` del body se ignora |

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `correo` | string | sí | formato de correo, hasta 150 |
| `nombreCompleto` | string | sí | hasta 150 |
| `rol` | `Rol` | sí | según la tabla de acceso |
| `sucursalId` | UUID | si `rol` = `BRANCH_ADMIN` | |
| `nit` | string | no | regla de NIT; vacío = `CF` |
| `dpi` | string | no | 13 dígitos |
| `telefono` | string | no | regla de teléfono |
| `direccion` | string | no | hasta 200 |

```json
{
  "correo": "admin.xela@transporte.gt",
  "nombreCompleto": "Carlos Méndez",
  "rol": "BRANCH_ADMIN",
  "sucursalId": "1b4e28ba-2fa1-41d2-883f-0016d3cca427",
  "telefono": "5598-7766"
}
```

**`201`** — `UsuarioResponse` con `perfil` y `confirmado: false`; encabezado
`Location: /api/usuarios/{id}`.

| Estado | `detail` | Cuándo |
|---|---|---|
| `403` | `No tienes permiso para esta acción` | Un `BRANCH_ADMIN` pide un rol que no es `CASHIER`, o es otro rol |
| `422` | `Un administrador del sistema crea administradores del sistema o de sucursal` | Un `SYS_ADMIN` pide `CASHIER`, `DRIVER` o `CUSTOMER` |
| `422` | `Indica la sucursal` | `BRANCH_ADMIN` sin `sucursalId` |
| `422` | `No existe esa sucursal` | |
| `422` | `La sucursal está inactiva` | También si un `BRANCH_ADMIN` crea un cajero y su propia sucursal está inactiva |
| `409` | correo, NIT o DPI repetidos | |
| `422` | el mensaje de formato del NIT | |
| `503` | el genérico | Redis no pudo guardar el enlace; la cuenta no se crea |

**Efectos:** guarda la cuenta pendiente con su perfil (incompleto si faltan datos) y el enlace de 72 h en
Redis, y emite E3 `identity.usuario.creado` (correo de bienvenida).

### `PATCH /api/usuarios/{id}`

Cambia el rol o la sucursal de una cuenta de personal.

**Acceso:** `SYS_ADMIN`.

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `rol` | `Rol` | sí | `SYS_ADMIN`, `BRANCH_ADMIN` o `CASHIER` |
| `sucursalId` | UUID | si `rol` es `BRANCH_ADMIN` o `CASHIER` | existente y activa; se ignora para `SYS_ADMIN` |

```json
{ "rol": "BRANCH_ADMIN", "sucursalId": "1b4e28ba-2fa1-41d2-883f-0016d3cca427" }
```

**`200`** — `UsuarioResponse` sin `perfil`.

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No existe ese usuario` | |
| `422` | `Solo se cambia el rol entre administrador del sistema, administrador de sucursal y cajero` | La cuenta es `DRIVER` o `CUSTOMER`, o se pide uno de esos roles |
| `422` | `Indica la sucursal` · `No existe esa sucursal` · `La sucursal está inactiva` | |
| `409` | `Debe quedar al menos un administrador del sistema activo` | Es el último `SYS_ADMIN` activo y confirmado, y deja de serlo |
| `409` | `La sucursal debe conservar al menos un administrador activo` | Es el último `BRANCH_ADMIN` activo de su sucursal y cambia de rol o de sucursal |
| `409` | `El usuario cambió mientras tanto. Vuelve a cargarlo e intenta de nuevo` | Otro admin lo modificó durante la operación |
| `503` | el genérico | Redis no respondió; no cambia nada |

**Efectos:** cierra **todas** las sesiones del usuario, porque sus tokens traen el rol y la sucursal
viejos, así que tiene que volver a iniciar sesión. No emite eventos.

### `PATCH /api/usuarios/{id}/estado`

Activa o desactiva una cuenta.

**Acceso:** `SYS_ADMIN` cualquier cuenta salvo la de un chofer. `BRANCH_ADMIN` solo los cajeros de su
sucursal.

| Campo | Tipo | Obligatorio |
|---|---|---|
| `activo` | boolean | sí |

```json
{ "activo": false }
```

**`200`** — `UsuarioResponse` sin `perfil`.

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No existe ese usuario` | |
| `403` | `No tienes permiso para esta acción` | Un `BRANCH_ADMIN` sobre algo que no es un cajero de su sucursal |
| `409` | `La cuenta de un chofer se activa o desactiva desde el registro del chofer` | Cuenta `DRIVER`: la gobierna fleet-service (E5) |
| `409` | `Debe quedar al menos un administrador del sistema activo` | Al desactivar al último `SYS_ADMIN` activo y confirmado |
| `409` | `La sucursal debe conservar al menos un administrador activo` | Al desactivar al último `BRANCH_ADMIN` activo de la sucursal |
| `409` | `El usuario cambió mientras tanto. Vuelve a cargarlo e intenta de nuevo` | |
| `503` | el genérico | Redis no respondió; no cambia nada |

**Efectos:**

- Desactivar cierra **todas** las sesiones del usuario. Desde ese momento no puede iniciar sesión, pedir
  recuperación ni usar un enlace.
- Activar no toca las sesiones.
- Repetir el estado actual no hace nada.
- No emite eventos.

---

## Sucursales — `/api/sucursales`

Cada cambio emite E1 `identity.sucursal.guardada` con la versión de la sucursal, que fleet, trip y
finance usan para mantener su réplica.

### `GET /api/sucursales`

Lista completa, ordenada por nombre, sin paginar.

**Acceso:** cualquier usuario autenticado. Un cliente la usa para elegir sucursal al pedir un alquiler.

| Query | Tipo | Descripción |
|---|---|---|
| `activa` | boolean | opcional; sin él trae activas e inactivas |

**`200`** — arreglo de `SucursalResponse` (ejemplo en [modelos](#sucursalresponse)).

### `GET /api/sucursales/{id}`

**Acceso:** cualquier usuario autenticado.

**`200`** — `SucursalResponse`.

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No existe esa sucursal` | |

### `POST /api/sucursales`

Crea la sucursal **y su primer administrador** en una sola transacción: si algo choca, no se crea ninguno
de los dos, y así ninguna sucursal queda sin administrador.

**Acceso:** `SYS_ADMIN`.

`sucursal`:

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `codigo` | string | sí | hasta 10; letras, números y guiones (`solo letras, números y guiones`); se guarda en mayúsculas |
| `nombre` | string | sí | hasta 100 |
| `departamento` | string | sí | hasta 50 |
| `municipio` | string | sí | hasta 50 |
| `direccion` | string | sí | hasta 200 |
| `telefono` | string | no | regla de teléfono |
| `latitud` | número | sí | de −90 a 90, hasta 6 decimales |
| `longitud` | número | sí | de −180 a 180, hasta 6 decimales |

`administrador`:

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `correo` | string | sí | formato de correo, hasta 150 |
| `nombreCompleto` | string | sí | hasta 150 |
| `telefono` | string | no | regla de teléfono |

```json
{
  "sucursal": {
    "codigo": "xela-01",
    "nombre": "Quetzaltenango Centro",
    "departamento": "Quetzaltenango",
    "municipio": "Quetzaltenango",
    "direccion": "4a. calle 12-35, zona 1",
    "telefono": "7765-4321",
    "latitud": 14.834999,
    "longitud": -91.518757
  },
  "administrador": {
    "correo": "admin.xela@transporte.gt",
    "nombreCompleto": "Carlos Méndez",
    "telefono": "5598-7766"
  }
}
```

**`201`** — `SucursalCreadaResponse`; encabezado `Location: /api/sucursales/{id}`:

```json
{
  "sucursal": {
    "id": "1b4e28ba-2fa1-41d2-883f-0016d3cca427",
    "codigo": "XELA-01",
    "nombre": "Quetzaltenango Centro",
    "departamento": "Quetzaltenango",
    "municipio": "Quetzaltenango",
    "direccion": "4a. calle 12-35, zona 1",
    "telefono": "7765-4321",
    "latitud": 14.834999,
    "longitud": -91.518757,
    "activa": true,
    "creadoEn": "2026-10-07T14:58:02.117450Z"
  },
  "administrador": {
    "id": "a3bb189e-8bf9-4888-9912-ace4e6543002",
    "correo": "admin.xela@transporte.gt",
    "nombreCompleto": "Carlos Méndez",
    "rol": "BRANCH_ADMIN",
    "sucursalId": "1b4e28ba-2fa1-41d2-883f-0016d3cca427",
    "activo": true,
    "confirmado": false,
    "creadoEn": "2026-10-07T14:58:02.131904Z",
    "perfil": { "nit": "CF", "dpi": null, "telefono": "5598-7766", "direccion": null, "completo": false }
  }
}
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `409` | `Ya existe una sucursal con ese código` | No se crea nada |
| `409` | `Ya existe una sucursal con ese nombre` | No se crea nada |
| `409` | `Ya existe una cuenta con ese correo` | El correo del administrador; no se crea nada |
| `503` | el genérico | Redis no pudo guardar el enlace; no se crea nada |

**Efectos:** emite E1 (sucursal nueva) y E3 (bienvenida del administrador, con su enlace de 72 h).

### `PUT /api/sucursales/{id}`

Reemplaza los datos y la ubicación. No cambia el estado: eso va por `PATCH …/estado`.

**Acceso:** `SYS_ADMIN`.

**Body:** los mismos campos que `sucursal` en el alta. `telefono` ausente lo deja en `null`.

**`200`** — `SucursalResponse`.

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No existe esa sucursal` | |
| `409` | `Ya existe una sucursal con ese código` · `Ya existe una sucursal con ese nombre` | |
| `409` | `Alguien más modificó estos datos mientras tanto. Vuelve a cargarlos e intenta de nuevo` | Otro admin la guardó al mismo tiempo |

**Efectos:** emite E1 en cada guardado.

### `PATCH /api/sucursales/{id}/estado`

Activa o desactiva la sucursal.

**Acceso:** `SYS_ADMIN`.

| Campo | Tipo | Obligatorio |
|---|---|---|
| `activa` | boolean | sí |

```json
{ "activa": false }
```

**`200`** — `SucursalResponse`.

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No existe esa sucursal` | |

**Efectos:**

- Emite E1 solo si el estado cambió.
- Desactivar no toca a sus usuarios ni a sus buses. Los demás servicios dejan de ofrecerla para lo nuevo,
  y en identity ya no se le pueden crear ni asignar usuarios (`422 La sucursal está inactiva`).

---

## Tarifas — `/api/tarifas`

Tarifas versionadas por fecha de vigencia. Cambiar una tarifa agrega una versión; las anteriores nunca
se modifican, así que lo que ya se calculó con ellas no cambia.

### `GET /api/tarifas/vigentes`

La versión vigente de cada tipo a una fecha.

**Acceso:** `SYS_ADMIN`, `BRANCH_ADMIN`.

| Query | Tipo | Descripción |
|---|---|---|
| `fecha` | `AAAA-MM-DD` | opcional; hoy (Guatemala) por defecto |

```
GET /api/tarifas/vigentes?fecha=2026-10-15
```

**`200`** — arreglo de `TarifaResponse`, a lo sumo uno por tipo (en el orden `DEPRECIACION_KM`,
`ALQUILER_KM`, `ALQUILER_DIA`): la versión con el `vigenteDesde` más reciente que no pase de `fecha`. Un
tipo sin versión vigente no aparece, así que puede venir vacío.

```json
[
  { "id": 7, "tipo": "DEPRECIACION_KM", "valor": 1.2500, "vigenteDesde": "2026-10-01", "registradoEn": "2026-10-07T15:10:44.902331Z" },
  { "id": 5, "tipo": "ALQUILER_KM", "valor": 4.5000, "vigenteDesde": "2026-09-15", "registradoEn": "2026-09-14T20:01:13.551270Z" }
]
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `403` | `No tienes permiso para esta acción` | Otro rol |

### `GET /api/tarifas`

Historial de versiones.

**Acceso:** `SYS_ADMIN`.

| Query | Tipo | Descripción |
|---|---|---|
| `tipo` | `TipoTarifa` | opcional |

**`200`** — arreglo de `TarifaResponse`. Con `tipo`, las de ese tipo, la más reciente primero. Sin él,
agrupadas por tipo en orden alfabético (`ALQUILER_DIA`, `ALQUILER_KM`, `DEPRECIACION_KM`) y, dentro de
cada tipo, la más reciente primero.

### `POST /api/tarifas`

Agrega una versión de una tarifa.

**Acceso:** `SYS_ADMIN`.

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `tipo` | `TipoTarifa` | sí | |
| `valor` | número | sí | ≥ 0 (`debe ser mayor que o igual a 0`); hasta 8 enteros y 4 decimales (`valor numérico fuera de límites (se esperaba <8 dígitos>.<4 dígitos>)`) |
| `vigenteDesde` | `AAAA-MM-DD` | no | hoy (Guatemala) por defecto. Puede ser pasada o futura; una futura no aparece en `vigentes` hasta ese día |

```json
{ "tipo": "DEPRECIACION_KM", "valor": 1.25, "vigenteDesde": "2026-10-01" }
```

**`201`** — `TarifaResponse` (sin `Location`).

| Estado | `detail` | Cuándo |
|---|---|---|
| `409` | `Ya hay una versión de esa tarifa con esa fecha de vigencia` | Mismo tipo y misma `vigenteDesde` |

**Efectos:** guarda quién la registró (`X-User-Id`; no sale en la respuesta) y emite E2
`identity.configuracion.actualizada`, con el que finance y customer guardan la versión.

---

## Internas — `/internal`

Para otros servicios y para el operador. El gateway **no** las expone y no piden autenticación: en
producción solo se llega a ellas desde la red interna de Docker.

### `GET /internal/sucursales/{id}/administradores`

S7, `DirectorioUsuarios`: a quién escribirle el recordatorio de licencia de un chofer. notification-service
la consulta al momento de enviar, porque los administradores cambian.

**Llama:** notification-service, a `http://identity-service:8081`.

**`200`** — arreglo de `AdministradorResponse`, ordenado por nombre: los `BRANCH_ADMIN` activos de la
sucursal, también los que todavía no confirman su cuenta. Vacío si no hay ninguno.

```json
[
  { "usuarioId": "a3bb189e-8bf9-4888-9912-ace4e6543002", "nombreCompleto": "Carlos Méndez", "correo": "admin.xela@transporte.gt" }
]
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No existe esa sucursal` | |

### `GET /internal/sucursales/{id}`

El nombre de una sucursal, para los correos de sales-service. No es una de las llamadas S1–S7 del diseño:
llegó con la saga de cancelación de viaje (PR #1, `feature/implementacion-saga`).

**Llama:** sales-service (`IdentityDirectoryClient`). Pide el origen y el destino del viaje al armar el
aviso de una compra en línea y el de un boleto cancelado porque se canceló el viaje. Si identity no
responde o da un error, sales pone "Por confirmar" y manda el aviso igual.

**`200`** — `SucursalNombreResponse`, solo el nombre, de una sucursal activa o inactiva:

```json
{ "nombre": "Quetzaltenango Centro" }
```

| Estado | `detail` | Cuándo |
|---|---|---|
| `404` | `No existe esa sucursal` | |
| `400` | `Failed to convert 'id' with value: '<valor>'` | El id no es un UUID, p. ej. un registro viejo de sales con el nombre de la sucursal en vez de su id |

### `POST /internal/dlq/reprocesar`

Devuelve a la cola `identity.events` los mensajes de `identity.events.dlq`, una vez corregida la causa por
la que fallaron.

**Llama:** el operador, desde el servidor. Sin body. Desde `identity-service/` (la imagen trae `curl`):

```bash
docker compose exec identity-service curl -s -X POST localhost:8081/internal/dlq/reprocesar
```

**`200`**:

```json
{ "reprocesados": 3 }
```

**Efectos:**

- Mueve solo los mensajes que había al empezar. Uno que vuelve a fallar regresa a la DLQ después de sus
  reintentos y no se reprocesa en un ciclo sin fin.
- Cada mensaje sale de la DLQ solo después de que RabbitMQ confirma el reenvío, así que un fallo a la
  mitad no pierde ninguno.
- Si RabbitMQ no responde, da `500`.

---

## Eventos que consume

Lo que identity publica (E1–E4 y E17, por outbox) está en el
[catálogo del README del proyecto](../README.md#catálogo-de-eventos), con su
[sobre](../README.md#sobre-de-cada-evento). Consume un solo evento, desde la cola `identity.events`, enlazada a
`transporte.events` con `fleet.chofer.guardado`. Su DLQ es `identity.events.dlq`, por `transporte.dlx`.

Cada evento se aplica en una transacción con su inbox (`processed_event`): uno repetido no tiene efecto. Lo
transitorio (la base o Redis caídos) se reintenta a 1, 2, 4 y 8 s y después va a la DLQ. Lo que nunca va a
funcionar va directo a la DLQ, sin reintentos; se corrige la causa y se
[reprocesa la DLQ](#post-internaldlqreprocesar).

### E5 `ChoferGuardado`

`fleet.chofer.guardado`, de fleet-service: crea o actualiza la cuenta `DRIVER` del chofer. fleet lo publica por
su outbox desde el 2026-10-28, al dar de alta, editar, activar o desactivar un chofer (notification también lo
consume, para los recordatorios de licencia).

En el sobre, `aggregateId` es el `choferId` y `aggregateVersion` es la versión del chofer en fleet, que sube
con cada cambio.

| Campo del payload | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `choferId` | string | sí | El id del chofer en fleet **como texto**, tal cual: hoy su id numérico (`"3"`). De 1 a 64 caracteres. identity no lo interpreta: lo guarda, lo devuelve como `choferId` en `UsuarioResponse` y `PerfilResponse`, y lo pone en el claim `choferId` del JWT, que el gateway reenvía como `X-Chofer-Id` |
| `sucursalId` | UUID | sí | Una sucursal de identity (la de E1), no su nombre |
| `nombreCompleto` | string | sí | hasta 150 |
| `correo` | string o `null` | no | sin correo no hay cuenta; hasta 150, y no distingue mayúsculas |
| `telefono` | string o `null` | no | va al perfil de la cuenta nueva; hasta 15 |
| `activo` | boolean | sí | si falta se toma como `false`, que desactiva la cuenta: fleet tiene que mandarlo siempre |

Los demás campos del payload de fleet (`numeroLicencia`, `tipoLicencia`, `fechaVencimiento`…) se ignoran. Un
`nombreCompleto`, `correo` o `telefono` más largo que su límite falla al guardar: se reintenta y termina en
la DLQ.

```json
{
  "eventId": "6f1c2a9e-3b7d-4c5e-8a1f-2d3e4f5a6b7c",
  "type": "fleet.chofer.guardado",
  "schemaVersion": 1,
  "aggregateId": "3",
  "aggregateVersion": 2,
  "occurredAt": "2026-10-27T15:02:11Z",
  "producer": "fleet-service",
  "correlationId": "c0ffee00-1234-4abc-9def-001122334455",
  "payload": {
    "choferId": "3",
    "sucursalId": "1b4e28ba-2fa1-41d2-883f-0016d3cca427",
    "nombreCompleto": "Pedro Pérez",
    "correo": "pedro.perez@transporte.gt",
    "telefono": "5512-3456",
    "activo": true,
    "numeroLicencia": "A-1234567",
    "tipoLicencia": "A",
    "fechaVencimiento": "2027-03-31"
  }
}
```

**Efectos:**

| Caso | Qué hace |
|---|---|
| El chofer no tiene cuenta, trae correo y está activo | Crea la cuenta `DRIVER` pendiente en su sucursal, con el perfil incompleto (NIT `CF` y el teléfono), el enlace de bienvenida de 72 h en Redis y E3 `identity.usuario.creado`, con el que notification manda el correo. Al definir su contraseña, su JWT lleva `choferId` |
| No tiene cuenta, y viene sin correo o inactivo | Nada. El siguiente E5 que traiga correo y `activo: true` la crea |
| Tiene cuenta y la versión es mayor que la última aplicada | Actualiza el nombre, el correo (si trae uno), la sucursal y el estado. Si cambia de sucursal o se desactiva, cierra **todas** sus sesiones. No emite eventos |
| La versión es igual o menor que la última aplicada | Se descarta: un E5 viejo que llega tarde no pisa a uno nuevo |
| El mismo `eventId` otra vez | Se descarta (inbox) |

**Directo a la DLQ, sin reintentos:**

- El sobre o el payload no se pueden leer, o el sobre no trae `eventId`, `type` o `payload`.
- Falta `choferId`, `sucursalId` o `nombreCompleto`, o el `choferId` viene vacío o con más de 64 caracteres.
- `sucursalId` no es un UUID o no es una sucursal de identity.
- El correo ya es de otra cuenta, al crearla o al cambiárselo.

---

## Técnicas

| Método | Ruta | Qué devuelve |
|---|---|---|
| `GET` | `/actuator/health` | `200 {"status":"UP"}`, o `503 {"status":"DOWN"}` si cae la base, Redis o RabbitMQ. Público; lo usa el healthcheck de Compose. El gateway no lo expone |
| `GET` | `/v3/api-docs` | Definición OpenAPI 3 en JSON. Por el gateway: `/v3/api-docs/identity` |
| `GET` | `/swagger-ui.html` | Swagger UI del servicio. Las rutas protegidas se prueban con el botón *Authorize* (`bearerAuth`) desde el Swagger del gateway |
