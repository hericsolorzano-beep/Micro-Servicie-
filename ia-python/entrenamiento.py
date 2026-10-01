"""
Entrena los dos modelos del microservicio y los persiste en ./modelos.

Ejecutar:  python entrenamiento.py

Cada modelo se guarda con joblib (pickle optimizado para arrays de numpy,
que es justo lo que scikit-learn produce). El archivo resultante lo carga
despues main.py al arrancar el servidor.
"""

import os
import random

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline

random.seed(42)
np.random.seed(42)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELOS_DIR = os.path.join(BASE_DIR, "modelos")

# Palabras vacias del espanol. sklearn solo trae lista integrada para
# ingles, asi que se declara aqui. En un corpus de 86 frases, "el", "en",
# "de" y "que" aparecen en las tres clases: no aportan senal y solo anaden
# ruido.
#
# IMPORTANTE: no incluir negaciones ni adverbios con carga sentimental.
# "no" aparece 5 veces y todas en frases negativas; "sin" aparece 4 veces y
# todas en positivas. Quitarlas no es limpiar ruido: es borrar la senal
# justo en la clase que mas la necesita. Con ngram_range=(1,2) se
# destruyen ademas los bigramas "no llego", "no funciona", "no vale".
#
# Tampoco se listan palabras de un solo caracter ("a", "y", "o"): el
# token_pattern por defecto de sklearn (\b\w\w+\b) exige dos o mas, asi que
# nunca llegan a generarse.
STOP_WORDS_ES = [
    "al", "algo", "ante", "antes", "como", "con", "contra", "cual",
    "cuando", "de", "del", "desde", "donde", "dos", "el", "ella", "ellos",
    "en", "entre", "era", "es", "ese", "esta", "este", "esto", "hasta",
    "hay", "la", "las", "le", "les", "lo", "los", "mas", "me", "mi", "mis",
    "muy", "nos", "otro", "para", "pero", "por", "porque", "que",
    "quien", "se", "sea", "si", "sobre", "son", "su", "sus", "tambien",
    "tanto", "te", "tiene", "todo", "tu", "tus", "un", "una", "uno",
    "ya", "yo", "he",
]



# ---------------------------------------------------------------- FRAUDE
#
# Dataset sintetico con las cuatro senales que mas pesan en fraude real:
#   monto        - importe de la transaccion
#   hora         - hora local de la operacion (0-23)
#   pais         - codigo ISO de 2 letras
#   distancia_km - distancia fisica entre titular y pais de la transaccion
#
# Las correlaciones estan fijadas en los parametros de abajo para que el
# dataset sea reproducible y los pesos del modelo sean interpretables.

# Umbral del detector por reglas. Calibrado para que la tasa de fraude
# quede cerca del 6%, que es el orden de magnitud real en tarjetas.
UMBRAL_RIESGO = 15.7

# pais -> (prob_base_de_fraude, distancia_media_km)
PERFILES_PAIS = {
    "ES": (0.04, 120.0),
    "FR": (0.05, 250.0),
    "DE": (0.05, 480.0),
    "US": (0.09, 5600.0),
    "GB": (0.08, 1100.0),
    "MX": (0.28, 9200.0),
    "BR": (0.31, 8700.0),
    "NG": (0.46, 5200.0),
    "RU": (0.52, 6100.0),
    "CN": (0.34, 9900.0),
}

# distancing por franja horaria: de madrugada es mucho mas sospechoso
PESO_HORA = {
    "noche": (0, 5, 1.9),
    "manana": (6, 11, 1.0),
    "tarde": (12, 17, 0.9),
    "noche_2": (18, 21, 1.1),
    "madrugada": (22, 23, 1.7),
}


def _distancia_para_hora(hora: int) -> float:
    """Las madrugada se asocia con viajes lejanos de urgencia."""
    if hora <= 5:
        return 2600.0
    if hora >= 22:
        return 1900.0
    return 180.0


def generar_transacciones(n: int = 12000):
    """Sintetiza transacciones con etiqueta de fraude."""
    filas = []
    for _ in range(n):
        pais = random.choice(list(PERFILES_PAIS.keys()))

        p_base, dist_media = PERFILES_PAIS[pais]
        hora = random.randint(0, 23)
        franja = "noche"
        for nombre, (ini, fin, peso) in PESO_HORA.items():
            if ini <= hora <= fin:
                franja = nombre
                break
        peso_franja = PESO_HORA[franja][2]

        distancia = max(
            0.0,
            random.gauss(_distancia_para_hora(hora) + dist_media * 0.25, 400.0),
        )

        # monto: la mayoria pequeno, una cola larga de operaciones grandes
        if random.random() < 0.85:
            monto = random.gauss(180.0, 150.0)
        else:
            monto = random.gauss(4200.0, 2200.0)
        monto = max(5.0, round(monto, 2))

        # lineal en log(monto) para capturar "muy grande = sospechoso"
        log_monto = np.log1p(monto) / 10.0

        # PUNTUACION DE RIESGO determinista: cada senal suma puntos.
        # Al ser una funcion pura de las features, el modelo SI puede
        # aprenderla. (Un sigmoid aleatorio anade ruido irreducible y
        # ningun algoritmo podria superar ~65% de accuracy.)
        riesgo = (
            2.0 * p_base * 10.0
            + 1.15 * peso_franja
            + 0.95 * log_monto * 5.0
            + 0.85 * min(distancia / 5000.0, 2.0) * 3.0
        )

        # Regla de umbral dura, sin banda de ruido: la etiqueta es
        # exactamente la salida de un detector por reglas. Asi el modelo
        # tiene una funcion que aprender y las metricas son interpretables.
        etiqueta = 1 if riesgo > UMBRAL_RIESGO else 0

        filas.append((monto, hora, pais, round(distancia, 1), etiqueta))

    X = np.array([[m, h, p, d] for m, h, p, d, _ in filas], dtype=object)
    y = np.array([e for *_, e in filas], dtype=int)
    return X, y


def entrenar_fraude():
    print("=" * 62)
    print("MODELO 1: DETECCION DE FRAUDE (RandomForest)")
    print("=" * 62)

    X, y = generar_transacciones(n=60000)
    print(f"dataset: {len(y)} transacciones, {y.sum()} fraude ({y.mean():.1%})")

    X_ent, X_test, y_ent, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    # OneHotEncoder sobre el indice 2 (pais). ColumnTransformer permite
    # mezclar columnas numericas tal cual con categoricas codificadas.
    from sklearn.compose import ColumnTransformer
    from sklearn.preprocessing import OneHotEncoder

    pre = ColumnTransformer(
        transformers=[
            ("num", "passthrough", [0, 1, 3]),
            ("pais", OneHotEncoder(handle_unknown="ignore"), [2]),
        ]
    )

    modelo = Pipeline(
        steps=[
            ("pre", pre),
            (
                "clf",
                RandomForestClassifier(
                    n_estimators=120,
                    max_depth=12,
                    class_weight="balanced",
                    random_state=42,
                    n_jobs=-1,
                ),
            ),
        ]
    )

    modelo.fit(X_ent, y_ent)

    pred = modelo.predict(X_test)
    print(f"\naccuracy : {modelo.score(X_test, y_test):.4f}")
    print(classification_report(y_test, pred, target_names=["legitima", "fraude"]))
    print("matriz de confusion:")
    print(confusion_matrix(y_test, pred))

    ruta = os.path.join(MODELOS_DIR, "fraude.joblib")
    joblib.dump(modelo, ruta)
    tam = os.path.getsize(ruta) / 1024
    print(f"\nguardado en {ruta} ({tam:.0f} KB)")
    return modelo


# ----------------------------------------------------------- SENTIMIENTO

POSITIVOS = [
    "excelente producto muy recomendado",
    "encanta la calidad llego muy rapido",
    "buena experiencia compra perfecta",
    "mejor servicio que he tenido",
    "totalmente satisfecho con la compra",
    "rapido barato y de gran calidad",
    "increible atencion al cliente",
    "recomendaria sin duda alguna",
    "producto mejor de lo esperado",
    "todo perfecto muy antiguo cliente",
    "feliz con el resultado final",
    "supero mis expectativas claramente",
    "envio inmediato y producto perfecto",
    "calidad excepcional a muy buen precio",
    "compra excelente sin dudas",
    "encantado desde el primer uso",
    "magnifico volvere a comprar",
    "impecable se nota la calidad",
    "precioso y muy bien acabado",
    "fantastico resultado lo recomiendo",
    "genial todo llego a tiempo",
    "comodo y facil de usar",
    "acabado de gama alta",
    "entrega rapida y sin problemas",
    "super recomendado sin dudarlo",
    "la mejor compra del año",
    "todo perfecto muy satisfecho",
    "excelente relacion calidad precio",
    "me ha alegre mucho con la compra",
]

NEGATIVOS = [
    "producto de muy mala calidad",
    "no llego nunca terrible servicio",
    "estaba roto al momento de recibirlo",
    "un desastre pide un reembolso ya",
    "precio alto para lo que ofrece",
    "la peor compra que he hecho",
    "no funciona completamente inutil",
    "muy decepcionado con el producto",
    "empaque roto y producto danado",
    "atencion horrible nadie responde",
    "llego roto y nadie ayuda",
    "pesima calidad se rompio a los dias",
    "me arrepenti completamente de comprar",
    "no vale lo que cuesta evitar",
    "un fraude total con este producto",
    "experiencia terrible muy mal servicio",
    "horrible se rompio al usarlo",
    "fatal llego todo danado",
    "decepcionante no lo recomiendo",
    "trato de lo peor que he tenido",
    "lento caro y de mala calidad",
    "un desastre absoluto",
    "nadie me ayudo con la devolucion",
    "calidad malisima una verguenza",
    "producto de lo mas barato y peor",
    "no lo recomendaria a nadie",
    "calidad horrible muy mal comprado",
    "una perdida de tiempo y dinero",
    "peor experiencia de compra",
]

NEUTRALES = [
    "el producto llego en su caja",
    "recibi el paquete hoy por la manana",
    "pedido numero 12345 en proceso",
    "esta bien nada especial",
    "entrega realizada segun lo previsto",
    "el vendedor envio el producto ayer",
    "recibi la notificacion de entrega",
    "confirmo recepcion del articulo",
    "consulta sobre el estado del envio",
    "el manual viene incluido en la caja",
    "es un producto de gama media",
    "necesito mas informacion sobre esto",
    "pregunto por las opciones de color",
    "cualquier duda avisen por favor",
    "quiero conocer el plazo de entrega",
    "el vendedor me responde por correo",
    "solicito la factura de la compra",
    "me interesa comparar dos modelos",
    "el producto cumple la descripcion",
    "confirmo que la caja esta cerrada",
    "pregunto si hay garantia extendida",
    "recibi el numero de seguimiento hoy",
    "el aviso de entrega llego ayer",
    "necesito ayuda para completar el pago",
    "consulto el horario de atencion",
    "el envio esta siendo preparado",
    "quedo a la espera de novedades",
    "informo que todo esta en orden",
]



def entrenar_sentimiento():
    """
    Entrena el clasificador de sentimiento sobre un corpus de 86 frases
    escritas a mano.

    LIMITACION CONOCIDA, y conviene no olvidarla: 86 frases es muy poco
    para un problema de lenguaje natural. El ~67% que sale es honesto,
    pero no es un modelo de producción: solo reconoce vocabulario cercano
    al del corpus, y una frase de un usuario con otras palabras tendra
    menos confianza. Sube la cifra solo con mas y mas datos, no con mas
    repeticiones de las mismas.

    Para un despliegue real, la via es un transformer preentrenado
    (distilbert-base-multilingual, por ejemplo), que ya trae vocabulario
    amplio. El resto de la arquitectura no cambia: el endpoint sigue
    siendo /predict/sentimiento y Java no se entera.
    """
    print()
    print("=" * 62)
    print("MODELO 2: ANALISIS DE SENTIMIENTO (TF-IDF + LogisticRegression)")
    print("=" * 62)

    # Cada frase se repite para dar volumen al set, pero ANTES de dividir:
    # si dividieramos despues, la misma frase caeria en entrenamiento y
    # en test, y la accuracy mediria memorizacion en vez de
    # generalizacion. Primero partimos las frases unicas, luego repetimos.
    unicos = []
    for grupo, etiqueta in ((POSITIVOS, "positivo"), (NEGATIVOS, "negativo"), (NEUTRALES, "neutro")):
        for t in grupo:
            unicos.append((t, etiqueta))

    datos_ent, datos_test = train_test_split(
        unicos,
        test_size=0.2,
        random_state=42,
        stratify=[etiqueta for _, etiqueta in unicos],
    )

    REPETICIONES = 24
    textos = [t for t, _ in datos_ent] * REPETICIONES
    etiquetas = [e for _, e in datos_ent] * REPETICIONES

    # El set de prueba son frases UNICAS que el modelo nunca vio.
    X_test = [t for t, _ in datos_test]
    y_test = [e for _, e in datos_test]
    print(
        f"dataset: {len(unicos)} frases unicas | "
        f"entrenamiento {len(textos)} documentos | "
        f"prueba {len(X_test)} frases"
    )

    # Pipeline = dos pasos en serie: vectorizar, despues clasificar.
    # Es clave que el vectorizador se ajuste SOLO con datos de entrenamiento,
    # asi que va dentro del Pipeline y no por fuera.
    modelo = Pipeline(
        steps=[
            (
                "tfidf",
                TfidfVectorizer(
                    ngram_range=(1, 2),
                    min_df=1,
                    sublinear_tf=True,
                    strip_accents="unicode",
                    lowercase=True,
                    # Palabras vacias del espanol (declaradas arriba). En un corpus de 86
                    # frases, "el", "en", "de" y "que" aparecen en las tres
                    # clases y no aportan senal. sklearn solo trae lista
                    # integrada para ingles, de ahi la constante propia.
                    stop_words=STOP_WORDS_ES,
                ),
            ),
            (
                "clf",
                LogisticRegression(
                    max_iter=1000, class_weight="balanced", C=4.0
                ),
            ),
        ]
    )

    modelo.fit(textos, etiquetas)

    pred = modelo.predict(X_test)
    print(f"\naccuracy : {modelo.score(X_test, y_test):.4f}")
    print(classification_report(y_test, pred))

    # Dejamos el vectorizador listo para consultar palabras clave.
    nombres = modelo.named_steps["tfidf"].get_feature_names_out()
    pesos = modelo.named_steps["clf"].coef_
    print("\ntop palabras por clase:")
    for i, clase in enumerate(modelo.named_steps["clf"].classes_):
        top = np.argsort(pesos[i])[-6:][::-1]
        print(f"  {clase:9s}: {', '.join(nombres[j] for j in top)}")

    ruta = os.path.join(MODELOS_DIR, "sentimiento.joblib")
    joblib.dump(modelo, ruta)
    tam = os.path.getsize(ruta) / 1024
    print(f"\nguardado en {ruta} ({tam:.0f} KB)")
    return modelo


if __name__ == "__main__":
    os.makedirs(MODELOS_DIR, exist_ok=True)
    entrenar_fraude()
    entrenar_sentimiento()
    print("\nEntrenamiento completo.")