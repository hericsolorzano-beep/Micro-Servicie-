"""
Motor de sentimiento, con dos implementaciones intercambiables.

Por que dos: el clasificador TF-IDF es diminuto (16 KB), arranca en
milisegundos y responde en 3 ms, pero solo reconoce el vocabulario de sus
86 frases de entrenamiento. El transformer es lo contrario: pesa ~1 GB,
arranca en segundos y responde en ~300 ms, pero generaliza a texto libre.

La politica de este modulo es: usar el transformer, y si por lo que sea
no esta disponible, caer al clasificador pequeno. Es degradacion en vez
de caida. Un servicio que devuelve 503 porque el modelo grande no arranco
es peor que uno que responde con precision menor pero util.

Cambiar de motor es una linea de configuracion, no un cambio de codigo.
"""

import logging
import os
import time

import joblib

log = logging.getLogger(__name__)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELOS_DIR = os.path.join(BASE_DIR, "modelos")

# Transformer multilingue ya entrenado en sentimiento. Trae las etiquetas
# en ingles, que se traducen a nuestro contrato al predecir.
#
# Se eligió este y no otro por una razón concreta: su id2label es
# {0: negative, 1: neutral, 2: positive}, que coincide con las tres
# clases que ya usábamos. Un modelo de dos clases (positivo/negativo)
# obligaría a inventar una regla para "neutro", y esa regla sería nuestra
# decisión, no la del modelo.
MODELO_TRANSFORMER = "cardiffnlp/twitter-xlm-roberta-base-sentiment"

TRADUCCION_ETIQUETAS = {
    "positive": "positivo",
    "neutral": "neutro",
    "negative": "negativo",
}

# El modelo acepta 512 tokens. Un texto mas largo no da error: se
# recorta. Sin esto, una reseña larga devolveria una excepcion.
MAX_TOKENS = 512


class MotorTFIDF:
    """Clasificador clasico: rapido, ligero, limitado a su corpus."""

    nombre = "tfidf_logreg_sentimiento"

    def disponible(self) -> bool:
        return self.modelo is not None

    def __init__(self):
        self.modelo = None
        ruta = os.path.join(MODELOS_DIR, "sentimiento.joblib")
        if not os.path.exists(ruta):
            log.warning("No se encontro %s; el motor TF-IDF no estara disponible", ruta)
            return
        try:
            self.modelo = joblib.load(ruta)
            log.info("Motor TF-IDF cargado")
        except Exception as e:
            log.error("Fallo al cargar el motor TF-IDF: %s", e)

    def predecir(self, texto: str) -> dict:
        prediccion = str(self.modelo.predict([texto])[0])
        probabilidades = self.modelo.predict_proba([texto])[0]
        clases = self.modelo.named_steps["clf"].classes_
        mapa = {str(c): round(float(p), 4) for c, p in zip(clases, probabilidades)}

        return {
            "sentimiento": prediccion,
            "confianza": mapa[prediccion],
            "probabilidades": mapa,
            "modelo": self.nombre,
        }


class MotorTransformer:
    """Transformer preentrenado: lento al arrancar, preciso con texto libre."""

    nombre = "xlm_roberta_sentimiento"

    def __init__(self, modelo_id: str = MODELO_TRANSFORMER):
        self.modelo_id = modelo_id
        self.pipeline = None
        self.traducción_valida = False

    def disponible(self) -> bool:
        return self.pipeline is not None

    def cargar(self) -> bool:
        """Carga el transformer. Devuelve False si algo falla.

        No propaga la excepcion a proposito: quien llama decide si puede
        seguir sin el. Que un modelo de 1 GB no se pueda descargar no
        debe impedir que el servicio arranque con el clasificador pequeno.
        """
        try:
            from transformers import pipeline

            inicio = time.perf_counter()
            self.pipeline = pipeline(
                "sentiment-analysis", model=self.modelo_id
            )
            carga_s = time.perf_counter() - inicio

            # Comprobacion de contrato: si las etiquetas del modelo no son
            # las que esperamos, sus nombres no se pueden traducir y
            # predecir() reventaria con KeyError en la primera peticion.
            # Mejor fallar aqui, al arrancar, donde el log lo deja claro.
            etiquetas = set(self.pipeline.model.config.id2label.values())
            if etiquetas != set(TRADUCCION_ETIQUETAS):
                log.error(
                    "El modelo %s tiene etiquetas %s, que no coinciden con "
                    "las esperadas %s. No se usara.",
                    self.modelo_id, etiquetas, set(TRADUCCION_ETIQUETAS),
                )
                self.pipeline = None
                return False

            self.traducción_valida = True
            log.info("Transformer cargado en %.1f s (%s)", carga_s, self.modelo_id)
            return True

        except Exception as e:
            log.warning(
                "No se pudo cargar el transformer %s (%s). "
                "Se usara el motor TF-IDF como respaldo.",
                self.modelo_id, type(e).__name__,
            )
            self.pipeline = None
            return False

    def predecir(self, texto: str) -> dict:
        # truncation=True: recorta a MAX_TOKENS en vez de fallar.
        resultado = self.pipeline(
            texto, truncation=True, max_length=MAX_TOKENS
        )[0]

        etiqueta_original = resultado["label"]
        etiqueta = TRADUCCION_ETIQUETAS[etiqueta_original]

        # El pipeline devuelve solo la mejor clase. Para dar las tres
        # probabilidades (que es parte de nuestro contrato) hay que
        # pedir todas las logits y aplicar softmax.
        probabilidades = self._probabilidades_completas(texto)

        return {
            "sentimiento": etiqueta,
            "confianza": round(float(resultado["score"]), 4),
            "probabilidades": probabilidades,
            "modelo": self.nombre,
        }

    def _probabilidades_completas(self, texto: str) -> dict:
        """Reconstruye el diccionario de las tres probabilidades.

        El pipeline con top_k=None devuelve todas las clases, que es lo
        que necesita nuestro consumidor. Se calcula a mano con el
        tokenizer y el modelo para no depender de que la version de
        transformers mantenga ese detalle de la API.
        """
        import torch

        entrada = self.pipeline.tokenizer(
            texto, return_tensors="pt", truncation=True, max_length=MAX_TOKENS
        )
        with torch.no_grad():
            logits = self.pipeline.model(**entrada).logits

        probabilidades = torch.softmax(logits, dim=-1)[0]

        id2label = self.pipeline.model.config.id2label
        return {
            TRADUCCION_ETIQUETAS[id2label[i]]: round(float(probabilidades[i]), 4)
            for i in range(len(probabilidades))
        }


class MotorConRespaldo:
    """Intenta el transformer y cae al clasificador si no esta disponible.

    Es el unico objeto que main.py necesita conocer. El endpoint no sabe
    quantos motores hay ni cual fallo: solo pide una prediccion.

    Aqui es donde el patron "degradar en vez de caerse" se decide de
    verdad, con una condicion concreta y no con optimism0.
    """

    def __init__(self, transformer: MotorTransformer, respaldo: MotorTFIDF):
        self.transformer = transformer
        self.respaldo = respaldo
        self.en_respaldo = False
        self.motivo_respaldo = None

    def predecir(self, texto: str) -> dict:
        if self.transformer.disponible():
            try:
                resultado = self.transformer.predecir(texto)
                if self.en_respaldo:
                    log.info("Transformer recuperado; se vuelve a usar")
                    self.en_respaldo = False
                    self.motivo_respaldo = None
                return resultado

            except Exception as e:
                # Fallo en TIEMPO DE EJECUCION, no de arranque. Se marca
                # el respaldo y se sigue: un texto que rompa el
                # transformer no debe tumbar el endpoint entero.
                if not self.en_respaldo:
                    log.error(
                        "El transformer fallo en inferencia (%s). "
                        "Se cae al motor TF-IDF.", type(e).__name__,
                    )
                    self.en_respaldo = True
                    self.motivo_respaldo = type(e).__name__

        if self.respaldo.disponible():
            resultado = self.respaldo.predecir(texto)
            resultado["nota"] = "motor de respaldo activo"
            return resultado

        raise RuntimeError(
            "Ningun motor de sentimiento esta disponible "
            f"(transformer: {self.transformer.disponible()}, "
            f"respaldo: {self.respaldo.disponible()})"
        )

    def estado(self) -> dict:
        return {
            "transformer_disponible": self.transformer.disponible(),
            "respaldo_disponible": self.respaldo.disponible(),
            "en_respaldo": self.en_respaldo,
            "motivo_respaldo": self.motivo_respaldo,
        }


def construir_motor(usar_transformer: bool = True) -> MotorConRespaldo:
    """Arma el motor con el transformer si se pide y si se puede cargar."""
    respaldo = MotorTFIDF()

    transformer = MotorTransformer()
    if usar_transformer:
        transformer.cargar()

    return MotorConRespaldo(transformer, respaldo)