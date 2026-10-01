"""
Evaluacion comparativa de los modelos de sentimiento.

Compara el clasificador TF-IDF + RegresionLogistica actual contra el
transformer, sobre el MISMO conjunto de evaluacion.

Por que este archivo existe: elegir un modelo por anotacion subjective
("este transformer es mejor") es una forma de decidir mal. Aqui ambos
corren sobre las mismas 15 frases y el numero decide.

Ejecutar:  ../.venv/bin/python comparar_modelos.py
"""

import os
import sys
import time

import joblib

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELOS_DIR = os.path.join(BASE_DIR, "modelos")

# Conjunto de evaluacion propio, escrito para esta comparacion y
# deliberadamente NO extraido del corpus de entrenamiento del TF-IDF.
# Incluye los dos casos que rompen a los clasificadores de bolsa de
# palabras: sarcasmo y pregunta retorica.
CASOS = [
    ("positivo", "llegó antes de lo previsto y el producto es una pasada"),
    ("positivo", "increíble atención, me resolvieron todo en el momento"),
    ("positivo", "la mejor compra que he hecho, calidad superó mis dudas"),
    ("positivo", "encantado con el resultado, volveré a comprar sin duda"),
    ("positivo", "rápido, barato y con una calidad que no esperaba"),
    ("negativo", "una pérdida de tiempo, se rompió al segundo día"),
    ("negativo", "nadie me contestó el correo, un desastre"),
    ("negativo", "carísimo para la calidad que tiene, me arrepentí"),
    ("negativo", "llegó roto y el vendedor se hizo el loco"),
    ("negativo", "producto de pésima calidad, se rompió a los días"),
    ("neutro", "quiero saber cuánto tarda el envío a Madrid"),
    ("neutro", "el paquete llegó en la dirección de siempre"),
    ("neutro", "¿cuál es el horario de atención al cliente?"),
    ("neutro", "necesito la factura de la compra de ayer"),
    ("neutro", "consulto si el producto tiene garantía extendida"),
]


def evaluar(modelo, predecir):
    """Devuelve (aciertos, detalle) donde predecir(texto) -> clase."""
    aciertos = 0
    detalle = []
    for esperada, texto in CASOS:
        predicha = predecir(texto)
        ok = predicha == esperada
        aciertos += ok
        detalle.append((ok, esperada, predicha, texto))
    return aciertos, detalle


def probar_tfidf():
    ruta = os.path.join(MODELOS_DIR, "sentimiento.joblib")
    if not os.path.exists(ruta):
        return None

    modelo = joblib.load(ruta)

    def predecir(texto):
        return str(modelo.predict([texto])[0])

    inicio = time.perf_counter()
    aciertos, detalle = evaluar(modelo, predecir)
    ms = (time.perf_counter() - inicio) * 1000 / len(CASOS)
    return {
        "nombre": "TF-IDF + LogReg",
        "aciertos": aciertos,
        "detalle": detalle,
        "ms_texto": ms,
        "nota": "86 frases de entrenamiento",
    }


def probar_transformer():
    try:
        from transformers import pipeline
    except ImportError:
        return None

    inicio_carga = time.perf_counter()
    try:
        clasificador = pipeline(
            "sentiment-analysis",
            model="cardiffnlp/twitter-xlm-roberta-base-sentiment",
        )
    except Exception as e:
        print(f"No se pudo cargar el transformer: {type(e).__name__}: {e}")
        return None
    carga_s = time.perf_counter() - inicio_carga

    # El modelo ya devuelve positive/neutral/negative, que coincide con
    # nuestro contrato. Si el mapeo fuera otro, habria que traducirlo
    # aqui, y ese punto es donde se suelen colar errores en silencio.
    etiquetas = clasificador.model.config.id2label
    esperados = {"positive", "neutral", "negative"}
    if set(etiquetas.values()) != esperados:
        print(f"AVISO: etiquetas del modelo {etiquetas} != {esperados}")
        return None

    # El modelo devuelve las etiquetas en INGLES. Sin esta traduccion,
    # la comparacion falla siempre y el transformer parece inútil: un 0%
    # que en realidad es un 100%. Es el tipo de bug que no da error, solo
    # un número que no cuadra, asi que se traduce explicitamente y se
    # comprueba que el mapa cubre todas las etiquetas del modelo.
    TRADUCCION = {
        "positive": "positivo",
        "neutral": "neutro",
        "negative": "negativo",
    }
    if set(etiquetas.values()) != set(TRADUCCION):
        print(f"AVISO: etiquetas del modelo {etiquetas} no cubren "
              f"{set(TRADUCCION)}; revisa TRADUCCION")
        return None

    def predecir(texto):
        # truncation=True es obligatorio: el modelo acepta 512 tokens y una
        # entrada mas larga daria error en vez de recortarse.
        etiqueta = clasificador(texto, truncation=True)[0]["label"]
        return TRADUCCION[etiqueta]

    # Descartamos las dos primeras llamadas: incluyen calentamiento.
    predecir("texto de calentamiento")
    predecir("otro texto de calentamiento")

    inicio = time.perf_counter()
    aciertos, detalle = evaluar(clasificador, predecir)
    ms = (time.perf_counter() - inicio) * 1000 / len(CASOS)
    return {
        "nombre": "XLM-RoBERTa (transformer)",
        "aciertos": aciertos,
        "detalle": detalle,
        "ms_texto": ms,
        "carga_s": carga_s,
        "nota": "entrenado en billions de tweets",
    }


def mostrar(resultados):
    if not resultados:
        print("No se pudo evaluar ningun modelo.")
        return

    print("=" * 68)
    print(f"EVALUACION: {len(CASOS)} frases de sentimiento")
    print("=" * 68)
    print(f"{'modelo':<28} {'acierto':>10} {'ms/texto':>10}")
    print("-" * 68)
    for r in resultados:
        pct = r["aciertos"] / len(CASOS)
        print(f"{r['nombre']:<28} {r['aciertos']:>3}/{len(CASOS)} {pct:>4.0%} "
              f"{r['ms_texto']:>9.1f}")
    print()

    for r in resultados:
        print(f"--- {r['nombre']}  ({r['nota']})")
        fallos = [d for d in r["detalle"] if not d[0]]
        if not fallos:
            print("    todos correctos")
        for _, esp, pred, texto in fallos:
            print(f"    {esp:8s} -> {pred:8s}  {texto[:52]}")
        print()

    mejor = max(resultados, key=lambda r: r["aciertos"])
    print(f"Mejor: {mejor['nombre']} con "
          f"{mejor['aciertos']}/{len(CASOS)} = {mejor['aciertos']/len(CASOS):.0%}")

    # Coste real delGanador, que es la parte que hay que mirar antes de
    # decidir: la latencia por texto y el arranque.
    print(f"\nLatencia por texto: {mejor['ms_texto']:.1f} ms")
    if "carga_s" in mejor:
        print(f"Tiempo de carga del modelo: {mejor['carga_s']:.1f} s "
              f"(solo al arrancar, luego se reutiliza)")


if __name__ == "__main__":
    resultados = [r for r in (probar_tfidf(), probar_transformer()) if r]
    mostrar(resultados)