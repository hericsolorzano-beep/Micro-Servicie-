-- Esquema inicial.
--
-- Flyway aplica estos archivos en orden por version y SOLO una vez. De ahi
-- sale su ventaja sobre ddl-auto=update, que puede alterar o perder datos
-- cuando el modelo cambia sin que nadie revise el cambio.
--
-- Nombres de tabla en singular ("transaccion", "usuario"): es convencion
-- habitual en PostgreSQL. Ademas, "usuario" y "orden" son palabras
-- reservadas en el dialecto, y el singular evita futuras colisiones.

CREATE TABLE usuario (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    nombre          VARCHAR(100) NOT NULL,
    -- El hash de la contrasena, NUNCA la contrasena. BCrypt produce
    -- 60 caracteres fijos con su propio salt, asi que dos usuarios con la
    -- misma contrasena tienen hashes distintos.
    hash_contrasena VARCHAR(100) NOT NULL,
    activo          BOOLEAN      NOT NULL DEFAULT TRUE,
    creado_en       TIMESTAMP    NOT NULL DEFAULT NOW(),

    -- El email identifica a una persona: dos cuentas con el mismo email
    -- son la misma cuenta, y permitirlo seria un fallo de seguridad.
    CONSTRAINT uq_usuario_email UNIQUE (email)
);

-- El indice no es decorativo: sin el, "WHERE email = ?" obliga a leer
-- toda la tabla. En autenticacion se ejecuta en cada peticion.
CREATE INDEX idx_usuario_email ON usuario (email);

CREATE TABLE transaccion (
    id                BIGSERIAL PRIMARY KEY,
    usuario_id        BIGINT       NOT NULL REFERENCES usuario (id),
    monto             NUMERIC(12, 2) NOT NULL,
    hora              INTEGER      NOT NULL,
    pais              VARCHAR(2)   NOT NULL,
    distancia_km      NUMERIC(10, 1) NOT NULL,

    -- Veredicto del modelo. Se guarda aunque la peticion no llegara a
    -- completarse, que es justo cuando mas informacion hace falta.
    es_fraude         BOOLEAN,
    probabilidad      NUMERIC(5, 4),
    nivel_riesgo      VARCHAR(20),
    modelo            VARCHAR(60),
    accion            VARCHAR(30),

    -- Cuando la IA estaba caida y la peticion no se pudo evaluar.
    -- Un NULL aqui significa "no se pudo decidir", que es informacion
    -- distinta de "se aprobo": NULL significa que no hubo
    -- evaluacion, no que la transaccion fuera limpia.
    error_analisis    VARCHAR(100),

    tiempo_inferencia_ms BIGINT,
    creado_en         TIMESTAMP    NOT NULL DEFAULT NOW(),

    -- CHECK en vez de solo validarlo en Java: la base de datos es el
    -- ultimo sitio donde se puede garantizar que nadie inserta un
    -- horario de 25 horas saltandose la API.
    CONSTRAINT chk_hora_rango    CHECK (hora BETWEEN 0 AND 23),
    CONSTRAINT chk_monto_positivo CHECK (monto > 0),
    CONSTRAINT chk_pais_formato  CHECK (pais ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_probabilidad  CHECK (probabilidad IS NULL OR (probabilidad >= 0 AND probabilidad <= 1))
);

-- Las consultas tipicas son "dame las transacciones de este usuario, las
-- mas recientes". El indice compuesto cubre ese caso sin necesidad de un
-- ORDER BY que obligaria a ordenar toda la tabla.
CREATE INDEX idx_transaccion_usuario_fecha ON transaccion (usuario_id, creado_en DESC);

-- Para auditar detecciones de fraude de forma agregada.
CREATE INDEX idx_transaccion_fraude ON transaccion (es_fraude) WHERE es_fraude IS NOT NULL;