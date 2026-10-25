# Configuracion de credenciales con .env

La API carga automaticamente `.env` al arrancar, antes de crear la conexion
a PostgreSQL. No requiere modificar `src/main/resources/application.properties`
ni configurar variables manualmente en IntelliJ.

## Preparacion

Para una instalacion nueva, desde la raiz del proyecto:

```powershell
Copy-Item .env.example .env
```

Completar `.env` con los datos de la base existente:

```dotenv
AULA_DB_URL=jdbc:postgresql://localhost:5432/postgres
AULA_DB_USER=postgres
AULA_DB_PASSWORD="REEMPLAZAR_CON_LA_CONTRASENA_LOCAL"
```

Se admiten comentarios con `#` y valores entre comillas dobles.
Usar comillas para una contrasena con espacios o `#`. Guardar como UTF-8
sin BOM. `.env` esta excluido de Git; `.env.example` solo contiene ejemplos.

## Ejecucion

Con JDK 21 y PostgreSQL disponibles, ejecutar desde la raiz del proyecto:

```powershell
.\gradlew.bat bootRun --console=plain
```

En IntelliJ, ejecutar `AulaAssistentApiApplication` con el directorio de
trabajo en la raiz del proyecto. Con un JAR, colocar `.env` en el directorio
desde el que se ejecuta `java -jar ruta/al/archivo.jar`.

La API usa el puerto configurado en `application.properties` (9091).
Flyway valida o aplica las migraciones al iniciar; la base de conexion debe
existir previamente.

## Carga y prioridad

`DotenvEnvironmentPostProcessor`, registrado en `META-INF/spring.factories`,
lee el archivo con `dotenv-java` y traduce:

| Variable | Propiedad de Spring |
| --- | --- |
| `AULA_DB_URL` | `spring.datasource.url` |
| `AULA_DB_USER` | `spring.datasource.username` |
| `AULA_DB_PASSWORD` | `spring.datasource.password` |

Los argumentos de arranque, las propiedades JVM y las variables del sistema
tienen prioridad sobre `.env`. `.env` tiene prioridad sobre
`application-local.properties` y `application.properties`. El archivo local
anterior sigue siendo compatible como alternativa para `AULA_DB_*`.

Si `.env` no existe, el arranque usa las otras fuentes de configuracion.
Si su contenido es invalido, el arranque se detiene con un mensaje sin
imprimir la linea que podria contener una credencial.

## Verificacion

```powershell
.\gradlew.bat test --console=plain
```

Las pruebas de configuracion verifican la carga y la prioridad de las
credenciales. Las pruebas de integracion tambien leen `.env`, crean sus
propios esquemas de PostgreSQL y los eliminan al finalizar.

Referencias: [EnvironmentPostProcessor de Spring Boot](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/EnvironmentPostProcessor.html)
y [dotenv-java](https://github.com/cdimascio/dotenv-java).
