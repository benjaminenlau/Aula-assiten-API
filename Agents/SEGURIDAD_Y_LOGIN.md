# Autenticacion y sesion unica

La API requiere autenticacion para consultar o modificar espacios, docentes
y asignaciones. Las cuentas de acceso son independientes del catalogo de
docentes: registrar un docente no crea automaticamente credenciales.

## Primer administrador

En `.env`, configurar una sola vez:

```dotenv
AULA_BOOTSTRAP_ADMIN_USER=administrador
AULA_BOOTSTRAP_ADMIN_PASSWORD="REEMPLAZAR_CON_UNA_CONTRASENA_SEGURA"
```

La contrasena debe tener entre 12 y 128 caracteres. No existe una clave
predeterminada. Ejecutar normalmente la API con JDK 21:

```powershell
.\gradlew.bat bootRun --console=plain
```

Flyway aplica V2 sin modificar V1 ni `application.properties`. Al arrancar,
se crea el primer ADMIN solo si no existe otro. El bootstrap no cambia la
contrasena de cuentas existentes ni vuelve a activar cuentas deshabilitadas.
Despues de la primera creacion se pueden retirar ambas variables de `.env`.
Si no hay variables ni administrador, la API sigue protegida y no habilita
un registro publico para resolverlo. Configurar ambas variables y reiniciar.

## Contrato HTTP

| Metodo | Ruta | Acceso y resultado |
| --- | --- | --- |
| POST | `/api/auth/login` | Publico; credenciales validas devuelven 200 y token |
| GET | `/api/auth/me` | Autenticado; datos publicos de la cuenta |
| POST | `/api/auth/logout` | Autenticado; revoca el token, 204 |
| GET | `/api/usuarios` | ADMIN; lista de cuentas sin hashes ni contrasenas |
| POST | `/api/usuarios` | ADMIN; crea cuenta, 201 |
| PATCH | `/api/usuarios/{id}/estado` | ADMIN; activa/desactiva y revoca la sesion, 200 |
| PUT | `/api/usuarios/{id}/password` | ADMIN; cambia contrasena, reinicia bloqueo y revoca sesion, 204 |

Solicitud de login:

```json
{"usuario":"administrador","password":"TU_CONTRASENA"}
```

Respuesta:

```json
{
  "token":"TOKEN_OPACO_GENERADO_POR_EL_SERVIDOR",
  "tipo":"Bearer",
  "expiraEn":"2026-10-26T00:00:00Z",
  "usuario":{"id":1,"usuario":"administrador","nombre":"Administrador","rol":"ADMIN","activo":true}
}
```

Enviar `Authorization: Bearer TOKEN` en todas las llamadas protegidas.
El token no se acepta por URL, parametros, cookies ni Basic Auth.
El cierre de sesion exige el token vigente; cerrar el navegador sin llamar
al endpoint no informa al servidor y la sesion permanece hasta caducar.

Para dar acceso a un profesional, el administrador envia:

```json
{
  "usuario":"profesional.uno",
  "nombre":"Nombre del profesional",
  "password":"UNA_CONTRASENA_SEGURA_DE_AL_MENOS_12_CARACTERES",
  "rol":"PROFESIONAL"
}
```

Nombre de acceso: entre 3 y 60 letras ASCII, numeros, puntos, guiones o
guiones bajos; se normaliza a minusculas. Nombre visible: hasta 160
caracteres, obligatorio. Roles disponibles: `ADMIN` y `PROFESIONAL`.
Ambos pueden operar la API de negocio; solo ADMIN gestiona cuentas.
No se permite que un administrador se desactive a si mismo ni eliminar
el ultimo administrador activo. No hay borrado fisico de cuentas.

Estado y cambio de contrasena reciben respectivamente:

```json
{"activo":false}
```

```json
{"password":"UNA_NUEVA_CONTRASENA_SEGURA"}
```

## Sesion unica y caducidad

Un segundo login con credenciales correctas devuelve **409 SESION_ACTIVA**
mientras la sesion anterior siga vigente. La primera sesion permanece
operativa; el segundo equipo no recibe token. La comprobacion y la creacion
se serializan bloqueando la fila de la cuenta en PostgreSQL. La clave
primaria de `sesion_usuario` refuerza que solo haya una fila por usuario,
incluso con varias instancias de la API o inicios concurrentes.

Cada sesion caduca a los **30 minutos sin actividad** o a las **8 horas desde
el login**, lo que ocurra primero. Una solicitud valida renueva actividad,
pero no amplia el limite absoluto. Logout, desactivacion y cambio de clave
revocan la sesion. Las sesiones sobreviven a reinicios porque se guardan
en PostgreSQL. No es necesario limpiar una sesion caducada manualmente:
el siguiente login la reemplaza y el token anterior deja de servir.

Esta regla controla una sesion activa por cuenta; no identifica un equipo
fisico. Un token robado se puede reutilizar hasta revocarlo o hasta su
caducidad. Para exigir identidad del dispositivo haria falta una fase
adicional con claves ligadas al dispositivo, por ejemplo WebAuthn/DPoP.

## Protecciones

- Contrasenas con Argon2id, sal aleatoria por cuenta, memoria 19 MiB,
  dos iteraciones y paralelismo 1. No se guardan contrasenas en claro.
- Tokens de 256 bits aleatorios. La base almacena solo el hash SHA-256.
- Cinco contrasenas incorrectas bloquean la cuenta durante 15 minutos.
  El contador es persistente; reiniciar la API no evita el bloqueo.
- Respuesta generica 401 para usuario inexistente, clave incorrecta,
  cuenta deshabilitada o bloqueada. Se calcula un hash ficticio cuando
  el usuario no existe para aproximar el coste de la comprobacion.
- Limite compartido de 30 intentos de login por minuto y direccion remota,
  incluyendo usuarios inexistentes. Retorna 429 y `Retry-After: 60`.
  No confia en `X-Forwarded-For` enviado por el cliente. Detras de un proxy,
  este limite agrupa las conexiones del proxy; configurar tambien limites
  en el proxy para la direccion real del cliente.
- Spring Security sin sesiones HTTP, login automatico ni Basic Auth.
  CSRF desactivado porque la autenticacion requiere una cabecera Bearer
  explicita y nunca una cookie enviada automaticamente por el navegador.
- Respuestas sin cache, encabezados de seguridad y permisos administrativos
  en la cadena de filtros. Los registros incluyen IDs y eventos, sin claves
  ni tokens. No habilitar logs de cuerpos/cabeceras de autenticacion.

En produccion, publicar exclusivamente mediante **HTTPS**, restringir
PostgreSQL y mantener el reloj de las instancias sincronizado. HTTP local
se conserva para desarrollo. El cliente web debe guardar el token en
memoria y evitar almacenamiento persistente accesible desde JavaScript.
Esta fase no implementa MFA, recuperacion por correo ni politicas de
dispositivos; el administrador puede restablecer una clave comprometida.

## Errores

Las respuestas siguen `application/problem+json`, con `status`, `detail`
y `codigo`. Un 401 exige iniciar sesion; un 403 indica falta de permiso.
El login duplicado devuelve 409 solo tras comprobar la contrasena.
Los datos invalidos devuelven 400 y los nombres de acceso duplicados 409.

## Verificacion

```powershell
.\gradlew.bat test --console=plain
```

Las pruebas usan esquemas temporales, sin modificar cuentas ni datos de
negocio en `public`. Cubren concurrencia de logins, caducidad absoluta y
por inactividad, revocacion, permisos, limites de intentos y persistencia.
Las pruebas REST existentes ahora autentican un profesional real.

Referencias: [Spring Security](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html),
[almacenamiento de contrasenas OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
y [sesiones OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).
