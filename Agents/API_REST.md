# 1. Fase de API REST

Se implementan servicios transaccionales, DTO, mapeadores, controladores y
manejo centralizado de errores para el modelo de persistencia confirmado.
Las rutas propuestas usan nombres en español bajo `/api`.

La fase conserva la migración V1 aplicada. PostgreSQL sigue garantizando la
integridad y la exclusión de cruces, incluso entre transacciones concurrentes.
El servicio valida los datos y transforma las entidades en respuestas dentro
de la transacción; la API no expone entidades JPA ni sus relaciones diferidas.

## 2. Contrato de recursos

`recurso` representa `espacios`, `docentes` o `asignaciones` en esta tabla:

| Método | Ruta | Resultado |
| --- | --- | --- |
| GET | `/api/{recurso}` | 200 y una lista JSON, vacía si no hay resultados |
| GET | `/api/{recurso}/{id}` | 200 y el recurso; 404 si no existe |
| POST | `/api/{recurso}` | 201 y el recurso creado; cabecera `Location` con su URL |
| PUT | `/api/{recurso}/{id}` | 200 y el recurso actualizado; 404 si no existe |
| DELETE | `/api/{recurso}/{id}` | 204 sin cuerpo; 404 si no existe; 409 si una asignación lo referencia |

Todos los identificadores deben ser enteros positivos. POST y PUT reciben
`Content-Type: application/json`. PUT requiere todos los campos del recurso.

### 2.1. Espacios y docentes

Solicitud para crear o actualizar:

```json
{"nombre": "Sala de sistemas 1"}
```

Respuesta:

```json
{"id": 1, "nombre": "Sala de sistemas 1"}
```

El nombre es obligatorio, no puede estar vacío o compuesto solo de espacios
y admite hasta 255 caracteres. Se conserva el texto recibido. No se impone
unicidad de nombres porque el modelo confirmado no la define. Los listados
se ordenan por identificador ascendente.

### 2.2. Asignaciones

Solicitud para crear o actualizar:

```json
{
  "espacioId": 1,
  "docenteId": 1,
  "inicio": "2026-10-26T08:00:00",
  "fin": "2026-10-26T10:00:00"
}
```

Respuesta:

```json
{
  "id": 1,
  "espacioId": 1,
  "docenteId": 1,
  "inicio": "2026-10-26T08:00:00",
  "fin": "2026-10-26T10:00:00"
}
```

Los cuatro campos de solicitud son obligatorios. El espacio y el docente
deben existir; una referencia inexistente devuelve 404. Las fechas se
interpretan como fecha y hora local de la sede y deben cumplir `inicio < fin`.
La API admite precisión de microsegundos (hasta seis decimales significativos
de segundo) y rechaza precisión superior para evitar redondeos al persistir.
Los intervalos son `[inicio, fin)`: 10–11 después de 08–10 está permitido.
Cambiar un horario o un recurso mediante PUT está sujeto a las mismas reglas
de cruce que POST. Si la actualización falla, se conserva el recurso previo.

## 3. Consulta del horario

GET `/api/asignaciones` admite estos filtros opcionales, combinados con AND:

| Parámetro | Tipo | Regla |
| --- | --- | --- |
| `espacioId` | Entero positivo | Selecciona asignaciones de ese espacio |
| `docenteId` | Entero positivo | Selecciona asignaciones de ese docente |
| `desde` | Fecha y hora ISO local | Debe enviarse junto con `hasta` |
| `hasta` | Fecha y hora ISO local | Debe ser posterior a `desde` |

Ejemplo de semana:

```http
GET /api/asignaciones?espacioId=1&desde=2026-10-26T00:00:00&hasta=2026-11-02T00:00:00
```

La consulta devuelve las asignaciones que se cruzan con `[desde, hasta)`:
`inicio < hasta AND fin > desde`, incluidas las que empiezan antes de `desde`
o terminan después de `hasta`. Una asignación que termina exactamente en
`desde`, o comienza exactamente en `hasta`, no se incluye. Los resultados se
ordenan por `inicio`, luego `id`, ascendentemente. Un filtro sin coincidencias
devuelve `[]`. Esta consulta aporta los intervalos ocupados para una vista
de calendario; no inventa duración de bloques ni horas de apertura.

## 4. Errores

Los errores de validación y negocio se devuelven como `application/problem+json`
con `status`, `title`, `detail`, `instance` y un `codigo` estable. Los errores
de campos incluyen una lista `errores` con `campo` y `mensaje`.

| HTTP | Código | Situación |
| --- | --- | --- |
| 400 | `DATOS_INVALIDOS` | Campos inválidos, JSON incorrecto, ID no positivo, fechas incorrectas, intervalo inválido o filtros incompletos |
| 404 | `RECURSO_NO_ENCONTRADO` | Recurso por ID o referencia solicitada que no existe |
| 409 | `CRUCE_ESPACIO` | PostgreSQL rechaza el cruce del mismo espacio |
| 409 | `CRUCE_DOCENTE` | PostgreSQL rechaza el cruce del mismo docente |
| 409 | `RECURSO_EN_USO` | Borrado o cambio incompatible con referencias existentes |
| 409 | `CONFLICTO_INTEGRIDAD` | Otro conflicto de integridad |

Ejemplo para 09–11 sobre una asignación 08–10 del mismo espacio y con otro
docente:

```json
{
  "type": "about:blank",
  "title": "Conflicto de horario",
  "status": 409,
  "detail": "El espacio ya tiene una asignación que se cruza con ese horario.",
  "instance": "/api/asignaciones",
  "codigo": "CRUCE_ESPACIO",
  "restriccion": "no_cruce_espacio"
}
```

El SQLSTATE `23P01` se traduce a 409 y al código de la exclusión correspondiente.
Cuando se violan las dos exclusiones a la vez, PostgreSQL puede informar
cualquiera de las dos. Se informa la que produjo el rechazo; no se hace una
consulta previa que pueda quedar desactualizada antes de guardar.

## 5. Ejecución y verificación

Con JDK 21, PostgreSQL y `application-local.properties` o `AULA_DB_PASSWORD` configurados como en el
[plan de persistencia](PLAN_ENTIDADES_Y_BD.md):

```powershell
.\gradlew.bat bootRun --console=plain
```

La API queda escuchando en `http://localhost:9091`; detener con Ctrl+C.
Se puede cambiar el puerto con `SERVER_PORT`. La conexión predeterminada
sigue siendo `jdbc:postgresql://localhost:5432/postgres`.

```powershell
.\gradlew.bat test --rerun-tasks --console=plain
```

La suite existente verifica Flyway, JPA y concurrencia en PostgreSQL. La nueva
suite `ApiRestIntegrationTests` arranca un servidor HTTP en un puerto aleatorio
y prueba el contrato completo contra PostgreSQL real. Cada suite crea un
esquema propio, comprueba que está seleccionado antes de modificar datos y
lo elimina al terminar. No introduce datos de prueba en las tablas de `public`.

## 6. Decisiones pendientes

Esta fase cubre la persistencia y API del modelo mínimo confirmado. Quedan
por definir capacidad, condiciones físicas, inventario, notificaciones,
autenticación/roles y recurrencia antes de implementar sus reglas. La consulta
de horario permite construir después el calendario del frontend.

Los listados de esta fase devuelven listas completas. La paginación y los
límites de consulta pueden incorporarse cuando se defina el volumen esperado.

## 7. Referencias

- [Spring MVC en Spring Boot](https://docs.spring.io/spring-boot/how-to/spring-mvc.html).
- [Errores REST con ProblemDetail](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).

## 8. Resultado de la verificación (2026-10-25)

Comando ejecutado con JDK 21 y PostgreSQL 18.6 local:

```powershell
.\gradlew.bat test --rerun-tasks --console=plain
```

| Suite | Pruebas | Fallos | Errores | Omitidas |
| --- | ---: | ---: | ---: | ---: |
| Persistencia, Flyway y concurrencia | 11 | 0 | 0 | 0 |
| API HTTP REST | 29 | 0 | 0 | 0 |
| Total | 40 | 0 | 0 | 0 |

La V1 conserva su checksum original. Las tablas de negocio de `public`
permanecen vacías y se eliminaron los esquemas temporales de pruebas y depuración.

### 8.1. Diagnóstico y corrección de las exclusiones

Se utilizó `ij-debugger` con logpoints sin suspensión para observar el rechazo
de 09–11 sobre 08–10 en un esquema de depuración aislado:

- En `ApiExceptionHandler.conflictoIntegridad`, Hibernate entregaba
  `FalloBaseDatos[estado=23P01, restriccion=null]`, por lo que la respuesta
  caía en el código genérico `CRUCE_HORARIO`.
- En esas mismas solicitudes, el campo estructurado
  `PSQLException.getServerErrorMessage().getConstraint()` devolvía
  `no_cruce_espacio` y `no_cruce_docente`, respectivamente.
- `falloBaseDatos` ahora recorre las causas hasta recuperar ese campo del
  controlador PostgreSQL. La traducción conserva los nombres exactos sin
  analizar mensajes dependientes del idioma.
- Las pruebas HTTP confirman `CRUCE_ESPACIO` y `CRUCE_DOCENTE` tanto al crear
  como al actualizar, y comprueban que un PUT rechazado conserva los datos previos.

Se retiraron los logpoints y se detuvo la sesión de depuración al finalizar.
