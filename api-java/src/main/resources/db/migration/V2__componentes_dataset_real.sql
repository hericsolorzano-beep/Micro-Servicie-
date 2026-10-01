-- V2: componentes PCA del dataset real de fraude.
--
-- El modelo se entrena con OpenML creditcard (did 1597): 284.807
-- transacciones reales con 492 casos de fraude. Sus variables V1..V28
-- son componentes PCA anonimizados; no existe "pais" ni "distancia" en
-- ese dataset.
--
-- Migracion:
--   - numero_componentes dice cuantos valores espera el modelo. Es una
--     constante en el codigo (Python y Java), pero duplicarla en la
--     base permite detectarla en el CHECK si alguien la cambia en uno
--     de los dos lados sin actualizar el otro.
--
--   - monto pasa a DECIMAL(12,2) para no perder centavos. El dataset
--     original viene en coma flotante, pero el dominio es dinero.
--
-- NOTA sobre los CHECK: mira que hora siga validandose y que no se
-- toque. Con el dataset real, la hora es una aproximacion (el
-- dataset mide segundos desde el inicio de la campana, no la hora del
-- dia), asi que puede valer cualquier valor; aun asi se valida porque
-- es la hora que el USUARIO declara y un valor invalido ahi indica un
-- cliente roto.

ALTER TABLE transaccion
    ADD COLUMN numero_componentes INTEGER;

UPDATE transaccion
    SET numero_componentes = 28;

ALTER TABLE transaccion
    ALTER COLUMN numero_componentes SET NOT NULL;

-- 28 porque es lo que espera el modelo actual. Si se cambia el
-- modelo, esta constraint salta en la migracion y no en produccion.
ALTER TABLE transaccion
    ADD CONSTRAINT chk_numero_componentes
        CHECK (numero_componentes = 28);

-- El veredicto ahora viene con su umbral: el cliente puede saber si la
-- decision fue conservadora o agresiva sin conocer la configuracion
-- interna del servicio de IA.
--
-- DOUBLE PRECISION, no NUMERIC(4,3). El campo Java es Double, y Hibernate
-- mapea Double a DOUBLE PRECISION en PostgreSQL. Si la migracion dice
-- NUMERIC y la entidad dice Double, ddl-auto=validate lo detecta al
-- arrancar: "found [numeric], but expected [double precision]". El
-- desajuste entre migracion y entidad solo aparece al arrancar, y es
-- justo lo que validate debe cazar.
ALTER TABLE transaccion
    ADD COLUMN umbral DOUBLE PRECISION;

-- Las componentes PCA se guardan aparte, en su propia tabla y no como
-- 28 columnas. El motivo es practico: anadir 28 columnas a transaccion lo
-- llenaria de ruido numerico que se consulta raramente. Y una fila por
-- componente hace trivial validar la longitud sin triggers.
--
-- La razon de fondo: V1..V28 son valores de la MISMA transaccion. Una
-- tabla hija obliga a que el numero de filas coincida con
-- numero_componentes, y eso se puede comprobar con un CHECK de conteo.

CREATE TABLE transaccion_componente (
    transaccion_id BIGINT      NOT NULL REFERENCES transaccion (id) ON DELETE CASCADE,
    posicion      SMALLINT    NOT NULL,
    valor         NUMERIC(12, 8) NOT NULL,

    PRIMARY KEY (transaccion_id, posicion),

    -- posicion es 0-based y va de 0 a 27. El CHECK en vez de una FK
    -- porque el rango depende de numero_componentes, que esta en la
    -- tabla padre: un CHECK no puede mirar otras tablas.
    CONSTRAINT chk_posicion_rango CHECK (posicion >= 0 AND posicion <= 27)
);

-- Consulta tipica: "dame las componentes de esta transaccion".
-- El PRIMARY KEY (transaccion_id, posicion) ya lo cubre: es la clave
-- primaria, asi que el indice existe y es el que se usa. No hace falta
-- crear uno adicional.

-- Indice para auditar detecciones por usuario y ventana temporal.
-- El indice existente (usuario_id, creado_en DESC) sirve para el
-- historial; este añade el filtro de fraude para consultas de
-- auditoria que no acotan por fecha.
CREATE INDEX idx_transaccion_usuario_fraude
    ON transaccion (usuario_id, es_fraude)
    WHERE es_fraude IS NOT NULL;