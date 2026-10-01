"""
Microservicio de IA. Expone inferencia por HTTP interno.

Arrancar:  uvicorn main:app --host 0.0.0.0 --port 8000

Este servicio NO se expone a internet. Solo lo consume Spring Boot.

Detalle clave del diseno: los modelos se cargan UNA vez durante el
arranque (evento startup) y quedan en memoria. Si los cargamos dentro
del handler de cada request, cada llamada pagaria el coste de deserializar
el .joblib desde disco (cientos de ms). En un servicio de inferencia eso
es la diferencia entre 5 y 5000 peticiones por segundo.
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

# Paises que el modelo de fraude conoce de verdad (los de
# PERFILES_PAIS en entrenamiento.py). Se declara aqui de forma explicita
# para que el limite sea visible en el borde del servicio.
PAISES_CONOCIDOS = {"ES", "FR", "DE", "US", "GB", "MX", "BR", "NG", "RU", "CN"}

# Variables globales que se llenan en el arranque. None significa "el
# servicio arranco pero no hay modelo", y el endpoint lo detecta.
modelo_fraude = None
motor_sentimiento = None

# El transformer pesa ~1 GB. En una maquina con poca RAM, o si se quiere
# arrancar rapido, se apaga con IA_TRANSFORMER=0 y el servicio usa solo
# el clasificador pequeno.
USAR_TRANSFORMER = os.getenv("IA_TRANSFORMER", "1") == "1"


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Carga los modelos antes de aceptar trafico.

    FastAPI usa este patron en vez de codigo a nivel de modulo para que
    las pruebas automatizadas puedan arrancar y parar el servicio sin
    arrastrar estado entre ejecuciones.
    """
    global modelo_fraude, motor_sentimiento

    inicio = time.perf_counter()

    # El modelo de fraude es obligatorio: sin el, el servicio no sirve de
    # nada y es mejor no arrancar que arrancar a medias.
    ruta_f = os.path.join(MODELOS_DIR, "fraude.joblib")
    if not os.path.exists(ruta_f):
        raise RuntimeError(
            "No se encontro el modelo de fraude. "
            "Ejecuta primero: python entrenamiento.py"
        )
    modelo_fraude = joblib.load(ruta_f)

    # El de sentimiento tiene respaldo, asi que su carga nunca aborta el
    # arranque: si el transformer no viene, se usa el clasificador TF-IDF
    # y el servicio sigue dando servicio con menor precision.
    motor_sentimiento = construir_motor(usar_transformer=USAR_TRANSFORMER)

    ms = (time.perf_counter() - inicio) * 1000
    print(f"[modelos] cargados en {ms:.0f} ms")
    print(f"[sentimiento] {motor_sentimiento.estado()}")

    # Importante: yield cede el control. Hasta aqui es codigo de arranque;
    # despues del yield corre el apagado.
    yield

    print("[modelos] descargados")


app = FastAPI(
    title="Microservicio IA",
    description="Inferencia de fraude y sentimiento. Uso interno.",
    version="1.0.0",
    lifespan=lifespan,
    # Este servicio es interno y se desplegaria detras de una API gateway
    # que autentica. Publicar /docs y /openapi.json sin autenticacion
    # permitiria enumerar el contrato a cualquiera que llegue a la red.
    # En local son utiles para depurar con el navegador.
    docs_url="/docs",
    openapi_url="/openapi.json",
)


# ------------------------------------------------------------ SCHEMAS
#
# Validar en el borde es obligatorio aunque la red sea interna: Java podria
# estar desactualizado respecto a este contrato, y un esquema roto que
# llega al modelo produce errores dentro de sklearn, no errores claros.


class TransaccionRequest(BaseModel):
    monto: float = Field(gt=0, le=1_000_000, description="Importe en la moneda de la cuenta")
    hora: int = Field(ge=0, le=23, description="Hora local de la operacion")
    pais: str = Field(
        min_length=2,
        max_length=2,
        pattern=r"^[A-Z]{2}$",
        description="Codigo ISO de 2 letras en mayusculas",
    )
    distancia_km: float = Field(ge=0, le=20_000, description="Distancia al pais de la transaccion")


class FraudeResponse(BaseModel):
    es_fraude: bool
    probabilidad: float = Field(ge=0.0, le=1.0)
    nivel_riesgo: str
    modelo: str


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
    """Sonda de salud.

    La consume el healthcheck de compose y el indicador de actuator de
    Java. Publica tambien el estado de cada motor, porque "sano" con el
    transformer en modo respaldo es una situacion distinta a "sano" con
    todo en su sitio: sirve, pero con menor precision.
    """
    estado_motor = motor_sentimiento.estado() if motor_sentimiento else None

    return {
        "estado": "ok",
        # El healthcheck de compose mira ESTE campo:asi un contenedor con
        # solo el modelo de respaldo se marca sano porque puede atender,
        # no porque tenga el motor principal.
        "modelos_cargados": modelo_fraude is not None and motor_sentimiento is not None,
        "fraude": modelo_fraude is not None,
        "sentimiento": motor_sentimiento is not None,
        "motor_sentimiento": estado_motor,
    }


@app.post("/predict/fraude", response_model=FraudeResponse)
def predecir_fraude(req: TransaccionRequest) -> FraudeResponse:
    if modelo_fraude is None:
        raise HTTPException(status_code=503, detail="El modelo de fraude no esta cargado")

    # El OneHotEncoder se entreno con handle_unknown="ignore": un pais no
    # visto se convierte en un vector de ceros y el modelo predice sin
    # error pero sin ninguna señal del pais. Mejor un 422 explicito que una
    # prediccion silenciosamente peor.
    if req.pais not in PAISES_CONOCIDOS:
        raise HTTPException(
            status_code=422,
            detail=f"Pais no soportado: {req.pais}. "
                   f"Conocidos: {', '.join(sorted(PAISES_CONOCIDOS))}")

    # Mismo orden de columnas que el entrenamiento. Este es el punto mas
    # fragile de todo el servicio: si el orden cambia aqui y no en
    # entrenamiento.py, el modelo predice sin error pero con el sentido
    # invertido. sklearn no valida nombres en un array numerico.
    # El test test_orden_de_columnas_esperado_por_el_transformer existe
    # precisamente para que un refactor aqui no pase desapercibido.
    X = [[req.monto, req.hora, req.pais, req.distancia_km]]

    prediccion = int(modelo_fraude.predict(X)[0])
    probabilidad = float(modelo_fraude.predict_proba(X)[0][1])

    if probabilidad >= 0.85:
        nivel = "critico"
    elif probabilidad >= 0.5:
        nivel = "alto"
    elif probabilidad >= 0.2:
        nivel = "medio"
    else:
        nivel = "bajo"

    return FraudeResponse(
        es_fraude=bool(prediccion),
        probabilidad=round(probabilidad, 4),
        nivel_riesgo=nivel,
        modelo="random_forest_fraude",
    )


@app.post("/predict/sentimiento", response_model=SentimientoResponse)
def predecir_sentimiento(req: TextoRequest) -> SentimientoResponse:
    if motor_sentimiento is None:
        raise HTTPException(
            status_code=503, detail="El motor de sentimiento no esta cargado")

    try:
        resultado = motor_sentimiento.predecir(req.texto)

    except RuntimeError as e:
        # Ningun motor disponible: eso si es un 503, porque el servicio no
        # puede contestar. El 503 lo ve Java y lo traduce a "IA no
        # disponible", que es lo que el cliente espera.
        raise HTTPException(status_code=503, detail=str(e)) from e

    except Exception as e:
        # Un fallo inesperado (texto que rompe el tokenizer, memoria...).
        # Se registra entero y se responde 500 sin detalles: la traza no
        # sale hacia el cliente.
        import logging
        logging.getLogger(__name__).exception("Fallo en inferencia de sentimiento")
        raise HTTPException(
            status_code=500,
            detail="Error interno en la inferencia de sentimiento") from e

    # El motor puede añadir "nota" cuando esta en modo respaldo. No forma
    # parte del esquema de respuesta (que es el contrato estable con
    # Java), asi que se descarta aqui.
    resultado.pop("nota", None)

    return SentimientoResponse(**resultado)