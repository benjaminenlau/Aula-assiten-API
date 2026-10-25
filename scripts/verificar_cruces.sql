-- Ejecutar con psql -X -v ON_ERROR_STOP=1 -f scripts/verificar_cruces.sql.
-- Se revierten todas las filas de prueba; las secuencias pueden avanzar.
BEGIN;

DO $$
DECLARE
    espacio_uno BIGINT;
    espacio_dos BIGINT;
    docente_uno BIGINT;
    docente_dos BIGINT;
    restriccion TEXT;
    estado TEXT;
BEGIN
    INSERT INTO espacio (nombre) VALUES ('Verificacion espacio 1') RETURNING id INTO espacio_uno;
    INSERT INTO espacio (nombre) VALUES ('Verificacion espacio 2') RETURNING id INTO espacio_dos;
    INSERT INTO docente (nombre) VALUES ('Verificacion docente 1') RETURNING id INTO docente_uno;
    INSERT INTO docente (nombre) VALUES ('Verificacion docente 2') RETURNING id INTO docente_dos;

    INSERT INTO asignacion (espacio_id, docente_id, inicio, fin)
    VALUES (espacio_uno, docente_uno, '2026-10-26 08:00', '2026-10-26 10:00');

    BEGIN
        INSERT INTO asignacion (espacio_id, docente_id, inicio, fin)
        VALUES (espacio_uno, docente_dos, '2026-10-26 09:00', '2026-10-26 11:00');
        RAISE EXCEPTION 'FALLO: se acepto el cruce de espacio';
    EXCEPTION WHEN exclusion_violation THEN
        GET STACKED DIAGNOSTICS restriccion = CONSTRAINT_NAME, estado = RETURNED_SQLSTATE;
        IF restriccion <> 'no_cruce_espacio' OR estado <> '23P01' THEN
            RAISE EXCEPTION 'Restriccion inesperada: %, SQLSTATE %', restriccion, estado;
        END IF;
        RAISE NOTICE 'OK no_cruce_espacio: 09-11 sobre 08-10 -> %', estado;
    END;

    BEGIN
        INSERT INTO asignacion (espacio_id, docente_id, inicio, fin)
        VALUES (espacio_dos, docente_uno, '2026-10-26 09:00', '2026-10-26 11:00');
        RAISE EXCEPTION 'FALLO: se acepto el cruce de docente';
    EXCEPTION WHEN exclusion_violation THEN
        GET STACKED DIAGNOSTICS restriccion = CONSTRAINT_NAME, estado = RETURNED_SQLSTATE;
        IF restriccion <> 'no_cruce_docente' OR estado <> '23P01' THEN
            RAISE EXCEPTION 'Restriccion inesperada: %, SQLSTATE %', restriccion, estado;
        END IF;
        RAISE NOTICE 'OK no_cruce_docente: 09-11 sobre 08-10 -> %', estado;
    END;

    INSERT INTO asignacion (espacio_id, docente_id, inicio, fin)
    VALUES (espacio_uno, docente_uno, '2026-10-26 10:00', '2026-10-26 11:00');
    RAISE NOTICE 'OK rango [): 10-11 consecutivo a 08-10 permitido';

    BEGIN
        INSERT INTO asignacion (espacio_id, docente_id, inicio, fin)
        VALUES (espacio_dos, docente_dos, '2026-10-26 12:00', '2026-10-26 12:00');
        RAISE EXCEPTION 'FALLO: se acepto inicio = fin';
    EXCEPTION WHEN check_violation THEN
        GET STACKED DIAGNOSTICS restriccion = CONSTRAINT_NAME;
        IF restriccion <> 'intervalo_valido' THEN
            RAISE EXCEPTION 'CHECK inesperado: %', restriccion;
        END IF;
        RAISE NOTICE 'OK CHECK inicio < fin: intervalo vacio rechazado (23514)';
    END;
END;
$$;

ROLLBACK;
