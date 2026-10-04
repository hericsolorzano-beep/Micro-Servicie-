"""
Microservicio de IA. Expone inferencia por HTTP interno.

Arrancar:  uvicorn main:app --host 0.0.0.0 --port 8000

Este servicio NO se expone a internet. Solo lo consume Spring Boot.

Los modelos se cargan UNA vez durante el arranque y quedan en memoria.
Cargarlos por peticion costaria cientos de ms en cada llamada, y en un
servicio de inferencia esa es la diferencia entre atender y no atender.

FORMATO DE LA PETICION DE FRAUDE

    {
      "componentes": [V1..V28],   // 28 floats, senal PCA anonimizada
      "hora": 12,                 // 0-23
      "monto": 150.0,
      "pais": "ES",               // informativo
      "distancia_km": 0.0         // informativo
    }

    La senal real esta en "componentes". "pais" y "distancia_km" se
    conservan en el contrato por compatibilidad, pero el modelo real no
    los usa: el dataset de referencia no los tiene. Documentarlo aqui
    evita que alguien asuma que el pais influye en la deteccion.
"""

import os
import time
from contextlib import asynccontextmanager

import joblib
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from motor_sentimiento import construir_motor

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELOS_DIR = os.path.join(BASE_DIR, "modelos")

# Nº de componentes PCA del dataset de referencia. Es el que el modelo
# espera, asi que llega en el esquema y no se valida despues.
N_COMPONENTES = 28

# Paises aceptados por el esquema. El modelo NO los usa (el dataset no
# tiene informacion de pais), asi que esta lista es de validacion de
# formato, no de disponibilidad real.
PAISES_VALIDOS = {
    "ES", "FR", "DE", "GB", "IT", "PT", "NL", "BE", "AT", "IE", "FI",
    "GR", "PL", "CZ", "SE", "NO", "DK", "CH", "US", "MX", "BR", "AR",
    "CL", "CO", "PE", "NG", "ZA", "EG", "MA", "IN", "CN", "JP", "AU",
    "NZ", "CA",
}

# El transformer pesa ~1 GB. En una maquina con poca RAM, o si se quiere
# arrancar rapido, se apaga con IA_TRANSFORMER=0.
USAR_TRANSFORMER = os.getenv("IA_TRANSFORMER", "1") == "1"

modelo_fraude = None
umbral_fraude = 0.5
motor_sentimiento = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Carga los modelos antes de aceptar trafico.

    Se usa este patron en vez de codigo a nivel de modulo para que las
    pruebas puedan arrancar y parar el servicio sin arrastrar estado
    entre ejecuciones.
    """
    global modelo_fraude, umbral_fraude, motor_sentimiento

    inicio = time.perf_counter()
    ruta_f = os.path.join(MODELOS_DIR, "fraude.joblib")
    ruta_s = os.path.join(MODELOS_DIR, "sentimiento.joblib")

    if not os.path.exists(ruta_f):
        raise RuntimeError(
            "No se encontro el modelo de fraude. Ejecuta: python entrenamiento.py")
    if not os.path.exists(ruta_s):
        raise RuntimeError(
            "No se encontro el modelo de sentimiento. "
            "Ejecuta: python entrenamiento.py")

    # El archivo guarda el modelo Y el umbral elegido en el entrenamiento.
    # Guardarlos juntos evita que la metrica publicada describa un
    # sistema que en produccion decide distinto.
    cargado = joblib.load(ruta_f)
    if isinstance(cargado, dict):
        modelo_fraude = cargado["modelo"]
        umbral_fraude = float(cargado["umbral"])
    else:
        # Formato antiguo: solo el modelo.
        modelo_fraude = cargado
        umbral_fraude = 0.5

    motor_sentimiento = construir_motor(usar_transformer=USAR_TRANSFORMER)

    ms = (time.perf_counter() - inicio) * 1000
    print(f"[fraude] cargado en {ms:.0f} ms (umbral {umbral_fraude:.3f})")
    print(f"[sentimiento] {motor_sentimiento.estado()}")

    yield

    print("[modelos] descargados")


app = FastAPI(
    title="Microservicio IA",
    description="Inferencia de fraude (datos reales) y sentimiento. Uso interno.",
    version="2.0.0",
    lifespan=lifespan,
    docs_url="/docs",
    openapi_url="/openapi.json",
)


# ------------------------------------------------------------ SCHEMAS


class TransaccionRequest(BaseModel):
    """
    Senal del modelo.

    componentes: V1..V28 del dataset de referencia, en ese orden. El
    orden importa y no lo comprueba el esquema: un array de 28 floats
    cumple la validacion tanto si viene bien ordenado como si no. El
    test de orden de columnas existe por eso.
    """
    componentes: list[float] = Field(
        min_length=N_COMPONENTES,
        max_length=N_COMPONENTES,
        description=f"Las {N_COMPONENTES} componentes PCA, en orden V1..V{N_COMPONENTES}"
    )

    monto: float = Field(gt=0, le=1_000_000, description="Importe de la transaccion")
    hora: int = Field(ge=0, le=23, description="Hora local de la operacion")
    pais: str = Field(
        pattern=r"^[A-Z]{2}$",
        description="Codigo ISO de 2 letras. NO lo usa el modelo.",
    )
    distancia_km: float = Field(
        ge=0, le=40_000,
        description="Distancia al pais. NO lo usa el modelo.",
    )


class FraudeResponse(BaseModel):
    es_fraude: bool
    probabilidad: float = Field(ge=0.0, le=1.0)
    nivel_riesgo: str
    modelo: str
    umbral: float = Field(description="Umbral usado para decidir es_fraude")


class TextoRequest(BaseModel):
    texto: str = Field(min_length=1, max_length=5000)


class SentimientoResponse(BaseModel):
    sentimiento: str
    confianza: float = Field(ge=0.0, le=1.0)
    probabilidades: dict[str, float]
    modelo: str


# ------------------------------------------------------------ ENDPOINTS


@app.get("/health")
def health():
    """
    Sonda de salud. La consultan el healthcheck de compose y el indicador
    de actuator de Java.

    Publica el estado de cada motor porque "sano con el transformer en
    respaldo" es una situacion distinta de "sano con todo en su sitio":
    sirve, pero con menos precision.
    """
    return {
        "estado": "ok",
        # El healthcheck de compose mira ESTE campo: un contenedor con
        # solo el modelo de respaldo se marca sano porque puede atender.
        "modelos_cargados": modelo_fraude is not None and motor_sentimiento is not None,
        "fraude": modelo_fraude is not None,
        "sentimiento": motor_sentimiento is not None,
        "umbral_fraude": umbral_fraude,
        "motor_sentimiento": motor_sentimiento.estado() if motor_sentimiento else None,
    }


@app.post("/predict/fraude", response_model=FraudeResponse)
def predecir_fraude(req: TransaccionRequest) -> FraudeResponse:
    if modelo_fraude is None:
        raise HTTPException(status_code=503, detail="El modelo de fraude no esta cargado")

    if req.pais not in PAISES_VALIDOS:
        raise HTTPException(
            status_code=422,
            detail=f"Pais no soportado: {req.pais}. "
                   f"Conocidos: {', '.join(sorted(PAISES_VALIDOS))}")

    # Fila en el orden que espera el entrenamiento: [Time, V1..V28, Amount].
    #
    # Time se reconstruye como hora*3600. Es una aproximacion: el dataset
    # real mide segundos desde la primera transaccion de la campana, no
    # la hora del dia. El modelo es robusto a esa diferencia porque Time
    # casi no entra entre las variables importantes, pero conviene
    # saberlo si alguien lee las metricas y pregunta por que no se envia
    # el Time original.
    tiempo = req.hora * 3600.0
    fila = [tiempo] + list(req.componentes) + [req.monto]

    probabilidad = float(modelo_fraude.predict_proba([fila])[0][1])

    # es_fraude se decide con el UMBRAL DEL ENTRENAMIENTO, no con
    # predict().
    #
    # predict() devuelve el argmax de predict_proba, es decir, decide en
    # 0.5. Eso ignora el umbral con el que se entreno y se eligio (0.30),
    # y deja el sistema haciendo EXACTAMENTE lo contrario de lo que dicen
    # las metricas publicadas: el recall de 0.84 se midio en 0.30, pero
    # el servicio corre en 0.5.
    #
    # Consecuencia observada: una transaccion con probabilidad 0.50
    # salia es_fraude=false pero nivel_riesgo="alto", y Java devolvia
    # APROBADA para algo que el nivel daba por sospechoso. Incoherente.
    prediccion = int(probabilidad >= umbral_fraude)

    # Los cortes de riesgo se DERIVAN del umbral, no se escriben a mano.
    #
    # Estaban fijos en 0.85 y 0.50, lo cual era coherente solo mientras el
    # umbral fuese <= 0.50. Con umbral 0.70 y probabilidad 0.60, la
    # respuesta decia es_fraude=false y nivel_riesgo="alto" en la misma
    # llamada: "no es fraude" y "riesgo alto" a la vez. Ese es justo el
    # descuadre que un comentario anterior dice haber corregido, volvio a
    # aparecer en cuanto el umbral subio.
    #
    # La regla que no puede fallar es la de la frontera: si es_fraude es
    # false, el nivel tiene que ser "bajo". Por eso "medio" empieza
    # exactamente en el umbral, y los siguientes van por proporcion
    # sobre ese mismo umbral.
    if probabilidad >= min(0.99, umbral_fraude * 2.5):
        nivel = "critico"
    elif probabilidad >= min(0.95, umbral_fraude * 1.5):
        nivel = "alto"
    elif probabilidad >= umbral_fraude:
        nivel = "medio"
    else:
        nivel = "bajo"

    return FraudeResponse(
        es_fraude=bool(prediccion),
        probabilidad=round(probabilidad, 4),
        nivel_riesgo=nivel,
        modelo="random_forest_fraude_datos_reales",
        umbral=umbral_fraude,
    )


@app.post("/predict/sentimiento", response_model=SentimientoResponse)
def predecir_sentimiento(req: TextoRequest) -> SentimientoResponse:
    if motor_sentimiento is None:
        raise HTTPException(
            status_code=503, detail="El motor de sentimiento no esta cargado")

    try:
        resultado = motor_sentimiento.predecir(req.texto)

    except RuntimeError as e:
        # Ningun motor disponible: si que es un 503, porque no se puede
        # contestar. Java lo traduce a "IA no disponible".
        raise HTTPException(status_code=503, detail=str(e)) from e

    except Exception as e:
        import logging
        logging.getLogger(__name__).exception("Fallo en inferencia de sentimiento")
        raise HTTPException(
            status_code=500,
            detail="Error interno en la inferencia de sentimiento") from e

    # El motor puede añadir "nota" en modo respaldo. No forma parte del
    # esquema (que es el contrato estable con Java), asi que se descarta.
    resultado.pop("nota", None)

    return SentimientoResponse(**resultado)