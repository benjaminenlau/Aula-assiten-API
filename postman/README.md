# Peticiones de AulaIA en Postman

## Importacion

1. Iniciar el backend desde la raiz del proyecto con `.\gradlew.bat bootRun`.
2. En Postman, elegir **Import** e importar estos archivos:
   - `AulaIA.postman_collection.json`: 31 peticiones, que cubren los 22
     contratos HTTP/ruta existentes, con ejemplos de filtros y seguridad.
   - `AulaIA.local.postman_environment.json`: plantilla de entorno local.
3. Seleccionar **AulaIA · Local** en el selector de entornos.
4. Completar los valores locales del entorno:

| Variable | Valor |
| --- | --- |
| `base_url` | `http://localhost:9091`, sin barra final |
| `admin_usuario` | Valor de `AULA_BOOTSTRAP_ADMIN_USER` del `.env` del backend |
| `admin_password` | Valor de `AULA_BOOTSTRAP_ADMIN_PASSWORD`, o la clave vigente si ya se cambio |
| `usuario_nuevo` | Nombre de acceso nuevo para la cuenta profesional de prueba |
| `usuario_password` | Contrasena inicial de esa cuenta, entre 12 y 128 caracteres |
| `password_nueva` | Otra contrasena para probar el restablecimiento, entre 12 y 128 caracteres |

Las contrasenas de la plantilla estan vacias y marcadas como secretas.
No se han copiado credenciales reales del backend. Conservar estos valores
localmente; no exportar el entorno completado ni el token para compartirlo.
Tambien se pueden completar las variables de la coleccion sin importar
el entorno, aunque se recomienda usar el entorno local para las claves.

## Recorrido recomendado

Ejecutar las carpetas en su orden numerico, o enviar peticiones individuales:

| Carpeta | Contenido |
| --- | --- |
| 01 · Autenticacion | Login del administrador y consulta de su cuenta |
| 02 · Usuarios | Listar, crear profesional, cambiar contrasena, desactivar y activar |
| 03 · Espacios | Crear, listar, obtener y actualizar |
| 04 · Docentes | Crear, listar, obtener y actualizar |
| 05 · Asignaciones y filtros | Crear, listar, obtener, actualizar y consultar por espacio, docente o rango |
| 06 · Comprobaciones de seguridad | Segundo login rechazado, primera sesion vigente y acceso anonimo rechazado |
| 07 · Limpieza y cierre | Eliminar asignacion, espacio y docente; desactivar cuenta de prueba y cerrar sesion |

El login guarda automaticamente `token`, `auth_usuario_id` y
`token_expira_en` en las variables de la coleccion. Las demas peticiones
heredan `Authorization: Bearer {{token}}`. Las altas exitosas guardan
`usuario_id`, `espacio_id`, `docente_id` y `asignacion_id` para reutilizarlos.
Los IDs no se toman del primer registro de un listado, para evitar operar
por defecto sobre registros preexistentes.

No crear variables de entorno llamadas `token` o con esos IDs: tendrian
prioridad sobre los valores capturados en la coleccion. Las variables
ausentes o IDs no positivos detienen la peticion con un mensaje explicativo.

Las peticiones ejecutan operaciones reales en la base configurada. La
limpieza utiliza los IDs guardados; comprobarlos si se cambiaron manualmente.
El orden del borrado evita que una asignacion impida borrar el espacio o
docente al que hace referencia. No existe DELETE de usuarios: la cuenta
profesional de prueba se desactiva al terminar.

## Cuerpos, fechas y permisos

Los cuerpos JSON se encuentran en **Body > raw**. Los scripts preparan las
claves de login y altas para admitir comillas o barras dentro de una
contrasena sin romper el JSON. Se puede cambiar el nombre visible o el rol
de una cuenta directamente en su Body; roles validos: ADMIN y PROFESIONAL.

Espacios y docentes reciben `{"nombre":"..."}`. Las asignaciones reciben
`espacioId`, `docenteId`, `inicio` y `fin`; los IDs deben ser numeros. PUT
de una asignacion requiere los cuatro campos completos.

Las fechas de ejemplo estan en las variables de la coleccion:

- `inicio` / `fin`: `2026-10-26T08:00:00` a `2026-10-26T10:00:00`.
- `inicio_actualizado` / `fin_actualizado`: 10:00 a 12:00 del mismo dia.
- `desde` / `hasta`: semana del 26 de octubre al 2 de noviembre de 2026.

Cambiar las fechas segun la prueba. Son fechas y horas locales de la sede,
sin sufijo `Z` ni desplazamiento horario. `inicio < fin`; `desde` y `hasta`
deben enviarse juntos y en orden. Los filtros se combinan con AND.

Solo ADMIN puede acceder a `/api/usuarios`. ADMIN y PROFESIONAL pueden
operar espacios, docentes y asignaciones. Los docentes del catalogo no
son automaticamente usuarios de acceso.

## Sesion unica y repeticion

El segundo login del mismo usuario devuelve `409 SESION_ACTIVA` y conserva
la primera sesion. Su script de comprobacion no sobrescribe el token. Para
probarlo desde otro equipo, importar la plantilla alli y enviar login con
las mismas credenciales mientras el primer equipo mantiene su sesion.

Ejecutar **Cerrar sesion** al terminar. La API revoca el token y el script
limpia la copia de la coleccion. Cerrar Postman sin enviar logout deja la
sesion abierta hasta 30 minutos de inactividad o 8 horas desde el login.

El restablecimiento de clave y los cambios de estado revocan la sesion del
usuario objetivo. Los ejemplos apuntan al profesional creado, no al
administrador conectado. No usar su ID si se desea conservar la sesion actual.

Al repetir el recorrido, elegir otro `usuario_nuevo`: una cuenta desactivada
sigue ocupando su nombre y el alta repetida devuelve `409 USUARIO_EXISTENTE`.
Una asignacion puede devolver 409 si sus horarios chocan con datos existentes;
en ese caso ajustar sus recursos u horas y reenviar. Cinco claves incorrectas
bloquean la cuenta 15 minutos; el login tambien tiene limite de 30 peticiones
por minuto y direccion remota. La coleccion no fuerza esos bloqueos.

## Inventario completo de recursos

| Metodo | Ruta | Cuerpo / parametros |
| --- | --- | --- |
| POST | `/api/auth/login` | `usuario`, `password` |
| GET | `/api/auth/me` | Sin cuerpo |
| POST | `/api/auth/logout` | Sin cuerpo |
| GET | `/api/usuarios` | Sin cuerpo; ADMIN |
| POST | `/api/usuarios` | `usuario`, `nombre`, `password`, `rol`; ADMIN |
| PATCH | `/api/usuarios/{id}/estado` | `activo`; ADMIN |
| PUT | `/api/usuarios/{id}/password` | `password`; ADMIN |
| GET | `/api/espacios` | Sin cuerpo |
| GET | `/api/espacios/{id}` | ID positivo |
| POST | `/api/espacios` | `nombre` |
| PUT | `/api/espacios/{id}` | `nombre` |
| DELETE | `/api/espacios/{id}` | Sin cuerpo |
| GET | `/api/docentes` | Sin cuerpo |
| GET | `/api/docentes/{id}` | ID positivo |
| POST | `/api/docentes` | `nombre` |
| PUT | `/api/docentes/{id}` | `nombre` |
| DELETE | `/api/docentes/{id}` | Sin cuerpo |
| GET | `/api/asignaciones` | Opcionales: `espacioId`, `docenteId`, `desde`, `hasta` |
| GET | `/api/asignaciones/{id}` | ID positivo |
| POST | `/api/asignaciones` | `espacioId`, `docenteId`, `inicio`, `fin` |
| PUT | `/api/asignaciones/{id}` | `espacioId`, `docenteId`, `inicio`, `fin` |
| DELETE | `/api/asignaciones/{id}` | Sin cuerpo |

Errores generales: 400 datos invalidos, 401 autenticacion requerida,
403 permiso insuficiente, 404 recurso inexistente, 409 conflicto y
429 demasiados intentos. Las respuestas de error incluyen `detail` y `codigo`.

Referencia del formato importable: [Postman Collection v2.1](https://schema.postman.com/collection/json/v2.1.0/draft-07/docs/index.html).
