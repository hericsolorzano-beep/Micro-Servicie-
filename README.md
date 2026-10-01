# Microservicio Inteligente

API en Java (Spring Boot) que recibe datos de usuarios y delega la
inferencia a un servicio Python con modelos de scikit-learn.

```
microservicio/
├── ia-python/            Servicio de IA (interno, no expuesto)
│   ├── entrenamiento.py    Genera y guarda el modelo de fraude
│   ├── motor_sentimiento.py Transformer + respaldo, con degradación
│   ├── main.py             FastAPI: carga modelos, expone /predict/*
│   ├── comparar_modelos.py Compara TF-IDF vs transformer, y decide
│   ├── test_modelos.py     Tests del contrato y del orden de columnas
│   ├── test_motor.py       Tests del motor y de la degradación
│   ├── requirements.txt    Versiones fijadas
│   ├── modelos/            .joblib generados
│   ├── Dockerfile
│   └── .dockerignore
├── api-java/             API publica (:8080)
│   ├── pom.xml
│   ├── Dockerfile
│   ├── .dockerignore
│   └── src/main/
│       ├── java/com/ejemplo/microservicio/
│       │   ├── controller/   Endpoints REST
│       │   ├── service/      Reglas de negocio, resiliencia y salud
│       │   ├── repository/   Acceso a datos (Spring Data JPA)
│       │   ├── domain/       Entidades JPA
│       │   ├── client/       Cliente HTTP hacia Python
│       │   ├── security/     Autenticación por clave de API
│       │   ├── dto/          Contratos publicos y privados (separados)
│       │   ├── config/       Cliente HTTP, timeouts, contraseñas
│       │   └── exception/    Manejo centralizado de errores
│       └── resources/
│           ├── application.yml
│           └── db/migration/   Migraciones Flyway
├── postgres/             Imagen de PostgreSQL para desarrollo
├── docker-compose.yml
└── .venv/                Entorno Python local
```

## Ejecutar en local (sin Docker)

Dos terminales, en este orden.

```bash
# 1) Servicio de IA (carga los modelos al arrancar)
cd ia-python
../.venv/bin/python entrenamiento.py      # solo la primera vez
../.venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8000

# 2) API publica
cd api-java
mvn spring-boot:run
```

## Ejecutar con Docker

Primero, las credenciales. El repositorio no contiene ninguna: viven en
`.env`, que está en `.gitignore`.

```bash
cp .env.ejemplo .env
# genera una contraseña real para la base
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -base64 24)|" .env
sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$(openssl rand -base64 24)|" .env
```

Las dos claves deben ser **iguales** si quieres que la aplicación conecte
con la base. Si prefieres separarlas (más realista en producción, donde la
aplicación no debería ser propietaria del esquema), crea un rol aparte:

```bash
docker exec -it postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "CREATE ROLE api LOGIN PASSWORD 'otra'; GRANT SELECT,INSERT,UPDATE ON ALL TABLES IN SCHEMA public TO api;"
```

Sin `.env`, `docker compose up` **falla** con un mensaje que dice qué
variable falta. Es deliberado: un valor por defecto en el compose
acabaría en producción.

```bash
docker compose up --build --wait
```

Requisitos: Docker, el plugin Compose, y tu usuario en el grupo `docker`
(`sudo usermod -aG docker $USER`, luego cerrar y reabrir sesión).

`api-java` espera a que `ia-python` y `postgres` estén *sanos*, y la sonda
comprueba el **cuerpo** de `/health`, no solo el código 200.

> Si cambias las credenciales de un volumen ya creado, el servidor sigue
> usando la contraseña original. Hay que borrar el volumen:
> `docker compose down -v` (esto **borra los datos**).

Comprobar que Python no está expuesto:

```bash
docker compose ps          # ia-python no muestra puertos publicados
curl http://localhost:8000/health   # falla: es interno
```

## Endpoints

| Método | Ruta | Descripción |
|--------|------|-------------|
| POST | `/api/transacciones` | Analiza una transacción: fraude, probabilidad, nivel de riesgo y acción de negocio |
| POST | `/api/texto` | Analiza sentimiento: positivo / negativo / neutro con probabilidades |
| GET | `/actuator/health` | Estado de la API, del servicio de IA y de la base de datos |

Con persistencia (versión 1 del contrato):

| Método | Ruta | Descripción |
|--------|------|-------------|
| POST | `/api/v1/usuarios` | Alta de usuario (201 + `Location`) |
| GET | `/api/v1/usuarios/{id}` | Perfil (404 si no existe) |
| POST | `/api/v1/usuarios/{id}/transacciones` | Analiza **y guarda** |
| GET | `/api/v1/usuarios/{id}/transacciones` | Historial paginado, con filtro `soloFraude` |

Variables de entorno con valor por defecto:

| Variable | Default | Para qué |
|----------|---------|----------|
| `IA_BASE_URL` | `http://localhost:8000` | Dónde está el servicio de IA |
| `DB_URL` | `jdbc:postgresql://localhost:5432/microservicio` | Base de datos |
| `DB_USER` / `DB_PASSWORD` | `microservicio` | Credenciales |
| `LOG_LEVEL` | `INFO` | Verbo del log de aplicación |
| `HEALTH_SHOW_DETAILS` | `never` | Detalles del actuator. Usa `always` solo en local |
| `SEGURIDAD_ACTIVADA` | `false` | Exige la cabecera `X-API-Key` |
| `SEGURIDAD_CLAVE` | (vacío) | La clave esperada. Sin ella, todo se rechaza |

Del lado de Python:

| Variable | Default | Para qué |
|----------|---------|----------|
| `IA_TRANSFORMER` | `1` | `0` arranca sin el transformer: 3 s y 90 MB en vez de 16 s y 660 MB, con menor precisión |

Internos (solo accesibles desde la red de compose):

| Método | Ruta | Descripción |
|--------|------|-------------|
| POST | `/predict/fraude` | Inferencia de fraude |
| POST | `/predict/sentimiento` | Inferencia de sentimiento |
| GET | `/health` | Sonda con estado de carga de modelos |

## Ejemplos

```bash
# Transacción legítima -> APROBADA
curl -s -X POST http://localhost:8080/api/transacciones \
  -H "Content-Type: application/json" \
  -d '{"monto":150,"hora":12,"pais":"ES","distancia_km":80}'

# Transacción sospechosa -> BLOQUEADA
curl -s -X POST http://localhost:8080/api/transacciones \
  -H "Content-Type: application/json" \
  -d '{"monto":9000,"hora":3,"pais":"NG","distancia_km":5200}'

# Sentimiento
curl -s -X POST http://localhost:8080/api/texto \
  -H "Content-Type: application/json" \
  -d '{"texto":"Pésima calidad, llegó roto y nadie responde"}'
```

## Números reales de los modelos

**Fraude (RandomForest)** — 99.7% accuracy sobre 60.000 transacciones sintéticas
con 6% de fraude. Recall 0.99 en la clase minoritaria, que es la que importa:
dejar pasar un fraude cuesta más que revisar una transacción legítima de más.

**Sentimiento (XLM-RoBERTa)** — 15/15 sobre el conjunto de comparación, y
funciona con sarcasmo y preguntas, cosa que el clasificador clásico no lograba.
Estable en Docker, con 660 MB de RAM.

Los dos modelos de sentimiento conviven, y el sistema **degrada en vez de
caerse**: si el transformer no carga o falla en inferencia, la petición se
atiende con el TF-IDF. Un 503 porque no se pudo descargar un modelo de 1 GB
sería peor que una respuesta con menor precisión.

| | TF-IDF + LogReg | XLM-RoBERTa |
|---|---|---|
| Tamaño | 16 KB | ~1 GB |
| Arranque | 2,8 s | 16,4 s |
| Latencia por texto | 3 ms | 296 ms (184 ms en lote) |
| RAM en contenedor | ~90 MB | 660 MB |
| Evaluación | 13/15 | **15/15** |

La latencia se midió, no se estimó. 296 ms cabe de sobra en el
`read-timeout` de 10 s que ya tenía el cliente Java.

Cambiar de motor es una variable de entorno, no un cambio de código:
`IA_TRANSFORMER=0` arranca con el clasificador clásico.

### Cómo se eligió, y no por gusto

`comparar_modelos.py` corre ambos modelos sobre las mismas 15 frases, que
están escritas para la comparación e incluyen sarcasmo
(*"totalmente horrible pero me encantó"*) y preguntas retóricas. La cifra
decide, no la preferencia.

Ese archivo también destapó un bug propio: el transformer devolvía
`positive`/`negative` en inglés y la comparación contra `positivo`/`negativo`
marcaba **0% para un modelo que acertaba el 100%**. Un número que no cuadra
y ningún error.

### Lo que la evaluación previa destapó

La primera versión del TF-IDF daba **100% de accuracy**, y era falso: las
frases estaban repetidas antes de dividir entrenamiento/prueba, así que la
misma frase caía en ambos conjuntos. Y su lista de stop words incluía `no`
y `sin`, que son las palabras con más señal del corpus (`no` aparece 5
veces, todas negativas). Arreglar ambas cosas subió el 67% → 72%. Ninguno
de los dos fallos daba error: eran números.

## Seguridad y resiliencia

### Autenticación

Cabecera `X-API-Key`, comparada en **tiempo constante** con
`MessageDigest.isEqual`. No es paranoia: `String.equals()` devuelve `false`
en cuanto encuentra el primer carácter distinto, así que el tiempo que
tarda revela cuántos caracteres lleva acertados el atacante, que puede
reconstruir la clave byte a byte midiendo peticiones.

Viene **desactivada por defecto** para que `curl` funcione en local. Ese
default tiene un riesgo evidente, y por eso `/actuator/health` publica si
la autenticación está activa: convierte un olvido de despliegue en algo
visible en un health check, que es donde se mira de verdad.

Para activarla:

```bash
SEGURIDAD_CLAVE=$(openssl rand -hex 32) SEGURIDAD_ACTIVADA=true docker compose up -d
```

Si se activa sin clave, **falla cerrado**: rechaza todas las peticiones.
Lo contrario (aceptar una clave vacía) abriría la API por un descuido de
configuración.

El 401 dice `No autorizado` y nada más. Untest detectó que mi mensaje
anterior, *"Credenciales ausentes o inválidas"*, sí distinguía los dos
casos y confirmaba al atacante que iba por buen camino.

### Resiliencia

Tres patrones, porque fallan cosas distintas:

| Patrón | Qué resuelve | Configurado |
|---|---|---|
| **Retry** | Fallos transitorios (Python reiniciándose) | 3 intentos, backoff exponencial |
| **Circuit breaker** | Fallo sostenido | Abre tras 50% de fallos, espera 30s |
| **Bulkhead** | Saturación | 10 llamadas concurrentes |

Medido con Python parado:

```
circuito cerrado:  0,64 s por petición  (3 reintentos × connect-timeout)
circuito abierto:  0,02 s por petición  (rechazo inmediato, sin tocar la red)
```

30× más rápido una vez abierto, y sin gastar un solo paquete hacia un
servicio que ya sabemos que está caído.

El **orden importa**: el circuit breaker envuelve al retry, para que una
petición del usuario cuente como **un** fallo y no como tres.

El bulkhead va en modo `SEMAPHORE`, no `THREADPOOL`: el segundo solo
funciona con llamadas asíncronas y lanza `IllegalStateException` en
ejecución sobre una llamada síncrona.

## Persistencia

PostgreSQL con Flyway para el esquema y `ddl-auto: validate` para comprobar
que el modelo coincide.

| | |
|---|---|
| Esquema | `usuario`, `transaccion` |
| Migraciones | `db/migration/V1__esquema_inicial.sql` |
| Pool | HikariCP, 10 conexiones máximas |

**`ddl-auto: validate`, no `update`.** Con `update`, Hibernate puede alterar
o borrar columnas sin que nadie lo haya decidido. Con `validate`, Flyway crea
el esquema y Hibernate solo comprueba que el modelo encaja. Con `none`, el
desajuste no se detecta hasta la primera consulta en producción.

Las restricciones viven **también** en la base de datos:

```sql
CONSTRAINT chk_hora_rango CHECK (hora BETWEEN 0 AND 23)
CONSTRAINT chk_monto_positivo CHECK (monto > 0)
CONSTRAINT chk_probabilidad CHECK (probabilidad IS NULL OR probabilidad BETWEEN 0 AND 1)
```

Validar en Java es cómodo; validar solo en Java deja un hueco por donde
alguien puede insertar una hora de 25 saltándose la API.

### Lo que se guarda cuando la IA falla

Esta es la decisión central del servicio, y el test que la verifica es
`guardaSinVeredictoCuandoLaIaFalla`.

Con la IA caída, la petición devuelve 503 **pero la operación se persiste**,
con `es_fraude` en `NULL` y `error_analisis` informado. Medido contra
PostgreSQL real:

```
 id | pais  |  monto  | es_fraude |  accion   |             error
----+-------+---------+-----------+-----------+--------------------------------
  1 | NG    | 9000.00 | t         | BLOQUEADA | -
  2 | ES    |  150.00 | f         | APROBADA  | -
  3 | FR    |  500.00 |           |           | El servicio de IA no respondio
```

La fila 3 es la importante. `NULL` no es `false`: significa **"nadie la
evaluó"**, no "evaluada y limpia". Si se guardara como `false`, un fraude
pasaría por aprobado en cuanto la IA tuviera un mal día.

Descartarla sería peor: se perdería el registro de todo lo intentado durante
la caída, que es justo lo que después hay que revisar a mano.

Por eso `TransaccionService.analizarYGuardar` **no** lleva `@Transactional`,
y el guardado usa `REQUIRES_NEW`. Si el análisis compartiera transacción con
la escritura, la excepción de la IA haría rollback y se perdería el registro.

### El health check detecta la caída real

Spring Boot trae un indicador de `DataSource` que devuelve `UP` solo con que
haya un bean configurado: si la base está caída pero el pool aún no ha
intentado conectar, dice que todo va bien. `BaseDatosHealthIndicator` ejecuta
un `SELECT 1` de verdad. Medido con PostgreSQL parado:

```
estado: DOWN
  baseDatos: DOWN  {'error': 'SQLTransientConnectionException'}
```

Solo registra el tipo de excepción, no la traza: la traza incluye la URL de
conexión con las credenciales.

## Decisiones de diseño

**Modelos cargados al arrancar, no por petición.** Un `.joblib` pesa
varios MB y tarda cientos de ms en deserializarse. Cargarlo dentro del
handler multiplicaría esa latencia por cada request.

**Contratos públicos y privados separados.** `SolicitudTransaccion` (lo
que ve el cliente) y `PeticionFraudePython` (lo que viaja al servicio de
IA) son tipos distintos. Un cambio interno en Python no rompe el contrato
público.

**Timeouts en el cliente HTTP.** Sin ellos, un Python lento agotaría el
pool de hilos de Tomcat y la API pública caería en cascada. Hay dos regímenes
distintos, medidos:

| Situación | Resultado medido |
|-----------|------------------|
| Python caído (en local, conexión rechazada) | 503 en ~34ms |
| Python parado (en Docker, hay red pero no hay servicio) | 503 en ~3s, el `connect-timeout` |
| Python colgado (acepta y no responde) | 503 tras el `read-timeout` de 10s |

Son tres escenarios distintos con tres costes distintos, y por eso los
dos timeouts hacen falta. En Docker, un contenedor parado no da conexión
rechazada: hay red y el paquete se pierde, así que se agota el
`connect-timeout` de 3s en lugar de fallar al instante.

**El protocolo se fija explícitamente en HTTP/1.1.** El `HttpClient` del JDK
negocia h2c por defecto y Uvicorn lo rechaza con un 422 de cuerpo vacío, que
*parece* que el body nunca se envió. El síntoma apunta al JSON; la causa está
en la negociación de protocolo.

**503 cuando la IA no responde.** 503 dice al cliente que el problema es
nuestra dependencia y que reintentar tiene sentido. Una respuesta nula o
incompleta de Python se traduce también a 503, nunca a un NPE con 500.

**Un nivel de riesgo desconocido escala, no aprueba.** Si Python añade un
nivel nuevo, la transacción marcada como fraude pasa a revisión humana.
Aprobar es el error caro y no se deshace.

**Los errores de cliente conservan su status.** `ManejadorErrores` extiende
`ResponseEntityExceptionHandler`: sin esa herencia, un handler genérico de
`Exception` convierte 404, 405 y 415 en 500, y el cliente no puede
distinguir "tu petición está mal" de "tenemos un fallo".

**El modelo no decide la acción de negocio.** Python devuelve probabilidad
y nivel de riesgo; `AnalisisService` decide si eso bloquea una cuenta. Esa
separación permite cambiar el modelo sin tocar las reglas.

**Sin datos sensibles en los logs.** No se registra ni el importe de la
transacción ni el texto analizado.

**Las anotaciones de resiliencia viven en su propia clase.** Resilience4j
funciona con proxies de Spring: si el método anotado se llama desde
dentro de la misma clase, el proxy no interviene y **la protección no se
aplica en silencio**. El código parece protegido y no lo está. Aislar las
llamadas en `ClienteIAResiliente` elimina esa trampa.

Esta no fue teórica: faltaba `spring-boot-starter-aop` en el POM. Sin él,
las tres anotaciones quedaban como metadatos que nadie leía. Los tests que
no contaban invocaciones pasaban. Solo `retryReintenta`, que cuenta
llamadas reales, lo detectó.

**Contrato validado en los dos lados.** Java valida los rangos y Pydantic los
revalida. Si aun así llega un país que el modelo no conoce, Python responde
422 en vez de predecir con un vector de ceros que perdería toda la señal de
ese país.

## Tests

```bash
cd api-java && mvn test                              # 49 tests
cd ia-python && ../.venv/bin/python test_modelos.py  # 11 tests
cd ia-python && ../.venv/bin/python test_motor.py    # 11 tests
```

Los tests de persistencia usan **Testcontainers** y levantan un PostgreSQL
real, así que **necesitan Docker**. Si tu sesión aún no pertenece al grupo
`docker`, o falla con *"Could not find a valid Docker environment"*.

**H2 se eliminó.** Con H2 los tests creaban las tablas desde el modelo de
JPA y **nunca ejecutaban las migraciones de Flyway**: el esquema de
producción no lo comprobaba nadie. Ahora Flyway aplica `V1__esquema_inicial.sql`
y `ddl-auto: validate` verifica que las entidades encajen. Comprobado:
romper la migración a propósito hace fallar los tests con
`column "columna_que_no_existe" does not exist`.

**Java (49).** 7 de lógica de negocio, 5 del cliente HTTP contra un
`MockRestServiceServer` (que serializa y deserializa de verdad, así que un
cambio de nombre de campo se detecta aquí), 10 del contrato HTTP público,
9 de seguridad, 6 de resiliencia y 10 de persistencia contra H2.

Los de persistencia no prueban que el CRUD funcione (eso lo da cualquier
framework). Prueban lo difícil: que la transacción se guarde **aunque la IA
falle**, que su `es_fraude` quede en `NULL` y no en `false`, que no cuente
como fraude, que la contraseña se guarde hasheada con BCrypt y que dos
usuarios con la misma contraseña tengan hashes distintos.

**H2 se eliminó.** Los tests usan PostgreSQL real vía Testcontainers, por un
motivo concreto: con H2, `ddl-auto: create-drop` construía las tablas desde
las entidades y **Flyway no se ejecutaba nunca**. Bastaba un error de
sintaxis o un tipo incompatible en la migración para que llegara hasta el
despliegue. Ahora se ejecuta lo mismo que en producción.

Los de seguridad usan el filtro de verdad, no una simulación: comprueban que
sin clave el endpoint **no se ejecuta** (`verifyNoInteractions`), que el 401
no distingue entre clave mala y clave ausente, y que activar sin clave
rechaza todo.

Los de resiliencia cuentan invocaciones reales al cliente. Eso es lo único
que distingue "el retry reintentó 3 veces" de "el retry no hace nada".

**Python (11).** El que importa es
`test_main_construye_la_fila_en_el_orden_entrenado`: intercepta la fila que
`main.py` realmente construye y la compara con los índices que espera el
`ColumnTransformer`. Lejos de ahí, un desajuste haría que el modelo
predijera con el sentido invertido sin lanzar ningún error. Comprobado:
reordenar esa fila hace fallar el test.

El resto cubre el contrato de campos contra los esquemas reales de FastAPI
(no contra un dict escrito en el propio test), que `PAISES_CONOCIDOS`
coincida con los países que el encoder sabe tratar, y una comprobación de
humo del clasificador.

**Degradación (11).** Lo que más importa aquí no es que el transformer
clasifique bien, sino qué pasa **cuando falla**: se le inyecta un motor que
lanza `RuntimeError` y se comprueba que la petición se sigue atendiendo con
el respaldo, que el estado lo refleja, y que tres fallos seguidos no dejan
el servicio a medias.

Ningún conjunto tarda más de unos segundos, porque ninguno entrena modelos
(el de `test_motor.py` tarda ~30 s solo en cargar el transformer).