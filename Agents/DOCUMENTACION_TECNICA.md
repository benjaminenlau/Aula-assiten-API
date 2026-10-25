#1 ACTUA COMO: Analista de Sistemas y desarrollador de software Senior, que usa como stack estas herramientas Java 21, Angular y PostgreSQL como base de datos

#2 CONTEXTO: Adjunto el documento de requerimientos de AulaIA, un sistema para asignar espacios de la sede Caucasia de la Corporación Universitaria Remington (2 salas de sistemas, 1 biblioteca y 1 auditorio) que evite cruces de horario.
Usa SOLO la información del documento. Si algo falta o es ambiguo, NO lo inventes: anótalo en "Preguntas abiertas" con un supuesto propuesto

#3 OBJETIVO: Desarrollar una aplicación web que asigne los espacios de la sede sin cruces de horario y muestre su disponibilidad semanal. a su vez en apartado de notificaciones se vaya mostrando los registros de cambios de aulas, cambio de profesores, o si cierta aula se encuentra disponible para su uso. a nivel informativo saber las condiciones físicas del aula (saber si los materiales se encuentran en buen estado)
a nivel informativo se que refleje la capacidad configurada a las aulas (configuración realizada por administración o por profesores). La idea es que sea responsiva de tal manera que se adapta a resoluciones de cualquier dispositivo

#4 TAREAS:
    1. Análisis del problema: problema, objetivo general, 3 a 5 objetivos específicos medibles, alcance (incluye / no incluye) y actores con su rol.
    2. Requisitos: funcionales (RF-01...) y no funcionales (RNF-01...: seguridad, rendimiento, usabilidad).
    3. Reglas de negocio (RN-01...), incluida la regla de cruce: hay choque si inicio < finExistente Y fin > inicioExistente; los bloques consecutivos sí se permiten.
    4. Historias de usuario en formato "Como <rol>, quiero <acción>, para <beneficio>", que cumplan INVEST, con criterios de aceptación Dado/Cuando/Entonces (incluye casos de error), prioridad MoSCoW y puntos de historia (1, 2, 3, 5, 8).
    5. Backlog organizado en épicas y una propuesta de 2 sprints.
    6. Diseño:
        a) Modelo de dominio: entidades, atributos, tipos y relaciones, como diagrama de clases en Mermaid.
        b) Modelo de datos en PostgreSQL: tablas, claves y restricciones (incluye una restricción EXCLUDE USING gist para impedir cruces) y el diagrama entidad-relación en Mermaid.
        c) Arquitectura: backend Spring Boot por capas (controller, service, repository, dto, mapper, exception) y frontend Angular (componentes, servicios, rutas). Justifica por qué monolito modular antes que microservicios.
        d) Contrato de la API REST: tabla con método, endpoint, descripción, códigos de respuesta (200, 201, 400, 404 y 409 cuando hay cruce) y ejemplos JSON.
    7. Riesgos y preguntas abiertas para el cliente

#5 RESTRICCIONES: Español. Nombres de código en inglés o en español, pero consistentes. Sin código de implementación todavía.

#6 FORMATO: Markdown con títulos numerados, tablas para requisitos, historias y API, y bloques mermaid para los diagramas.

#7 VERIFICACIÓN: Al final incluye una autoevaluación que confirme que (1) cada historia tiene al menos un criterio de aceptación, (2) cada RF está cubierto por una historia, (3) la regla de cruce aparece en historias, base de datos y API, y (4) se listaron todos los supuestos.
