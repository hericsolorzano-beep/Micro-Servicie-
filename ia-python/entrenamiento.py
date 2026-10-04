"""
Entrena los dos modelos y los persiste en ./modelos.

Ejecutar:  python entrenamiento.py

DATOS DE FRAUDE
    Vienen de OpenML (did 1597): 284.807 transacciones reales de
    tarjeteros europeos con 492 casos de fraude etiquetados. La tasa real
    es del 0.17%.

    Antes se usaba un dataset sintetico con un 6% de fraude. La
    diferencia no es academica: con 6%, un modelo que siempre dijera
    "no es fraude" acertaba el 94% de las veces y pareceria
    perfecto. Con 0.17%, ese mismo modelo obtiene 0.17%. El desbalance
    real es lo que hace que medir tenga sentido, y por eso el accuracy
    solo NO se reporta como metrica principal.

    Ver dataset_fraude.py para el detalle de las limitaciones.
"""

import os
import random
import time

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    classification_report,
    confusion_matrix,
    precision_recall_curve,
    roc_auc_score,
)
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

import dataset_fraude

random.seed(42)
np.random.seed(42)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELOS_DIR = os.path.join(BASE_DIR, "modelos")

# El dataset real no tiene pais ni distancia: V1..V28 son componentes
# PCA anonimizados. El esquema publico se mantiene con pais y distancia a
# cero, y el resto de la senal va en "componentes", que es donde vive la
# informacion real.
N_COMPONENTES = 28


# ---------------------------------------------------------------- FRAUDE


def entrenar_fraude():
    print("=" * 68)
    print("MODELO 1: DETECCION DE FRAUDE (RandomForest)")
    print("datos: OpenML creditcard (did 1597), transacciones reales")
    print("=" * 68)

    inicio = time.perf_counter()
    X, y = dataset_fraude.cargar_csv()

    X = np.array(X, dtype=np.float64)
    y = np.array(y, dtype=int)

    fraude = int(y.sum())
    print(f"\ndataset: {len(y)} transacciones, {fraude} fraude "
          f"({fraude/len(y):.4%})")
    print(f"carga:   {time.perf_counter() - inicio:.1f}s")

    # stratify mantiene la proporcion de fraude en ambos conjuntos. Sin
    # el, el conjunto de prueba podria tener por casualidad 20 casos de
    # fraude y las metricas serian ruido.
    X_ent, X_test, y_ent, y_test = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    # Columnas: [Time, V1..V28, Amount]
    # Time es una escala temporal en segundos: estandarizarla evita que
    # domine al resto por tener valores de miles.
    # StandardScaler directo sobre las 30 columnas. Sin ColumnTransformer:
    # no hay columnas categoricas que tratar, porque V1..V28 son
    # componentes PCA continuas y el pais no entra en el modelo.
    pre = StandardScaler()

    modelo = Pipeline(
        steps=[
            ("pre", pre),
            (
                "clf",
                RandomForestClassifier(
                    n_estimators=150,
                    # Sin max_depth: deja crecer los arboles. Con 492
                    # casos de fraude, limitar la profundidad ayudaba a
                    # sobreajustar menos; aqui no hace falta.
                    min_samples_leaf=1,
                    # class_weight="balanced" IMPORTA con 0.17% de
                    # fraude. Sin el, el modelo minimiza el error global
                    # learniendo a decir "no es fraude" siempre: sale
                    # con un recall de ~0 y el area bajo la curva ROC
                    # enganosamente alta, porque la clase negativa
                    # domina.
                    class_weight="balanced_subsample",
                    random_state=42,
                    n_jobs=-1,
                ),
            ),
        ]
    )

    print("\nreparticion: "
          f"{len(y_ent)} entrenamiento / {len(y_test)} prueba")
    print(f"fraude:     {int(y_ent.sum())} / {int(y_test.sum())}")

    inicio = time.perf_counter()
    modelo.fit(X_ent, y_ent)
    print(f"entrenamiento: {time.perf_counter() - inicio:.1f}s")

    prob = modelo.predict_proba(X_test)[:, 1]
    pred = modelo.predict(X_test)

    # ---------------------------------------------------- METRICAS
    #
    # El accuracy se imprime solo como referencia historica, no como
    # medida de calidad. Con 0.17% de fraude, un modelo que siempre dice
    # "no es fraude" saca 99.83%: numero impecable y modelo inútil.
    print("\n" + "-" * 68)
    print("METRICAS")
    print("-" * 68)

    print(f"accuracy:                  {modelo.score(X_test, y_test):.4f}"
          "   <- NO usar: 99.8% lo saca 'siempre no es fraude'")

    print(f"ROC AUC:                   {roc_auc_score(y_test, prob):.4f}")
    print(f"average precision:         {average_precision_score(y_test, prob):.4f}")
    print("   <- esta es la metrica util en desbalance extremo: es el"
          " area bajo la curva precision-recall")

    print("\nclassification report (umbral por defecto, 0.5):")
    print(classification_report(y_test, pred, target_names=["legitima", "fraude"],
                                digits=4))
    print("matriz de confusion:")
    print(confusion_matrix(y_test, pred))

    # Precision y recall tradeoff. Aqui el fraude perdido (falso negativo)
    # es mas caro que una revision innecesaria, asi que se baja el
    # umbral: se aceptan mas falsos positivos a cambio de detectar mas.
    precision, recall, umbrales = precision_recall_curve(y_test, prob)

    # "detectados" son SOLO los casos de fraude que caen por encima del
    # umbral. Antes se imprimia (prob >= umbral).sum(), que cuenta
    # TODAS las transacciones por encima del umbral, incluidas las
    # legitimas: en el umbral 0,50 daba 77 "detectados" cuando solo
    # 73 eran fraude y 4 eran falsos positivos. Una columna que suma
    # acierto y error, y lo llama deteccion.
    #
    # Los falsos positivos van en su propia columna, porque son el
    # coste real de bajar el umbral y mezclarlos con los aciertos es
    # justo lo que hace ilegible la tabla.
    n_fraude = int((y_test == 1).sum())
    print("\nprecision/recall por umbral:")
    print(f"  {'umbral':>8} {'precision':>10} {'recall':>8} "
          f"{'fraude':>8} {'falsos+':>9} {'perdidos':>9}")
    for objetivo in (0.50, 0.70, 0.80, 0.90, 0.95):
        i = np.argmin(np.abs(umbrales - objetivo))
        # Se usa el umbral REAL de la curva, no el objetivo pedido, que
        # no tiene por que existir entre los valores devueltos.
        u = umbrales[i]
        por_encima = prob >= u
        fraude_detectado = int((por_encima & (y_test == 1)).sum())
        falsos_positivos = int((por_encima & (y_test == 0)).sum())
        perdidos = n_fraude - fraude_detectado
        print(f"  {u:8.3f} {precision[i]:10.4f} {recall[i]:8.4f} "
              f"{fraude_detectado:>8} {falsos_positivos:>9} {perdidos:>9}")

    # El umbral elegido se guarda CON el modelo, no se recalcula en
    # produccion: si el servicio eligiera otro, la metrica publicada
    # estaria describiendo un sistema que no existe.
    UMBRAL_ELEGIDO = 0.30
    i = np.argmin(np.abs(umbrales - UMBRAL_ELEGIDO))
    print(f"\numbral elegido: {umbrales[i]:.3f}")
    print(f"  precision: {precision[i]:.4f}")
    print(f"  recall:    {recall[i]:.4f}  "
          f"({int((y_test == 1).sum() * recall[i])} de {int((y_test == 1).sum())} "
          f"fraudes detectados)")

    # Importancia de variables: el unico dato interpretable del dataset
    # es cuanto pesa Amount frente al resto.
    importancias = modelo.named_steps["clf"].feature_importances_
    orden = np.argsort(importancias)[::-1]
    print("\nvariables mas importantes:")
    for idx in orden[:8]:
        print(f"  {dataset_fraude.COLUMNAS[idx]:>10}  {importancias[idx]:.4f}")

    ruta = os.path.join(MODELOS_DIR, "fraude.joblib")
    joblib.dump({"modelo": modelo, "umbral": float(umbrales[i])}, ruta)
    tam = os.path.getsize(ruta) / 1024 / 1024
    print(f"\nguardado en {ruta} ({tam:.1f} MB)")

    return modelo, float(umbrales[i])


# ----------------------------------------------------------- SENTIMIENTO

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

POSITIVOS = [
    "excelente producto muy recomendado", "encanta la calidad llego muy rapido",
    "buena experiencia compra perfecta", "mejor servicio que he tenido",
    "totalmente satisfecho con la compra", "rapido barato y de gran calidad",
    "increible atencion al cliente", "recomendaria sin duda alguna",
    "producto mejor de lo esperado", "todo perfecto muy antiguo cliente",
    "feliz con el resultado final", "supero mis expectativas claramente",
    "envio inmediato y producto perfecto", "calidad excepcional a muy buen precio",
    "compra excelente sin dudas", "encantado desde el primer uso",
    "magnifico volvere a comprar", "impecable se nota la calidad",
    "precioso y muy bien acabado", "fantastico resultado lo recomiendo",
    "genial todo llego a tiempo", "comodo y facil de usar",
    "acabado de gama alta", "entrega rapida y sin problemas",
    "super recomendado sin dudarlo", "la mejor compra del año",
    "todo perfecto muy satisfecho", "excelente relacion calidad precio",
    "me ha alegre mucho con la compra",
]

NEGATIVOS = [
    "producto de muy mala calidad", "no llego nunca terrible servicio",
    "estaba roto al momento de recibirlo", "un desastre pide un reembolso ya",
    "precio alto para lo que ofrece", "la peor compra que he hecho",
    "no funciona completamente inutil", "muy decepcionado con el producto",
    "empaque roto y producto danado", "atencion horrible nadie responde",
    "llego roto y nadie ayuda", "pesima calidad se rompio a los dias",
    "me arrepenti completamente de comprar", "no vale lo que cuesta evitar",
    "un fraude total con este producto", "experiencia terrible muy mal servicio",
    "horrible se rompio al usarlo", "fatal llego todo danado",
    "decepcionante no lo recomiendo", "trato de lo peor que he tenido",
    "lento caro y de mala calidad", "un desastre absoluto",
    "nadie me ayudo con la devolucion", "calidad malisima una verguenza",
    "producto de lo mas barato y peor", "no lo recomendaria a nadie",
    "calidad horrible muy mal comprado", "una perdida de tiempo y dinero",
    "peor experiencia de compra",
]

NEUTRALES = [
    "el producto llego en su caja", "recibi el paquete hoy por la manana",
    "pedido numero 12345 en proceso", "esta bien nada especial",
    "entrega realizada segun lo previsto", "el vendedor envio el producto ayer",
    "recibi la notificacion de entrega", "confirmo recepcion del articulo",
    "consulta sobre el estado del envio", "el manual viene incluido en la caja",
    "es un producto de gama media", "necesito mas informacion sobre esto",
    "pregunto por las opciones de color", "cualquier duda avisen por favor",
    "quiero conocer el plazo de entrega", "el vendedor me responde por correo",
    "solicito la factura de la compra", "me interesa comparar dos modelos",
    "el producto cumple la descripcion", "confirmo que la caja esta cerrada",
    "pregunto si hay garantia extendida", "recibi el numero de seguimiento hoy",
    "el aviso de entrega llego ayer", "necesito ayuda para completar el pago",
    "consulto el horario de atencion", "el envio esta siendo preparado",
    "quedo a la espera de novedades", "informo que todo esta en orden",
]


def entrenar_sentimiento():
    """
    Clasificador de respaldo: rapido y ligero, para cuando el transformer
    no esta disponible.

    LIMITACION CONOCIDA: 86 frases escritas a mano dan ~67% de
    exactitud. El transformer (XLM-RoBERTa) da ~100% en las mismas
    pruebas. Este modelo existe para degradar con dignidad, no para
    competir con el principal.
    """
    print()
    print("=" * 68)
    print("MODELO 2: ANALISIS DE SENTIMIENTO (TF-IDF + LogisticRegression)")
    print("respaldo del transformer; se usa si el grande no esta disponible")
    print("=" * 68)

    unicos = []
    for grupo, etiqueta in ((POSITIVOS, "positivo"),
                            (NEGATIVOS, "negativo"),
                            (NEUTRALES, "neutro")):
        for t in grupo:
            unicos.append((t, etiqueta))

    datos_ent, datos_test = train_test_split(
        unicos, test_size=0.2, random_state=42,
        stratify=[etiqueta for _, etiqueta in unicos],
    )

    REPETICIONES = 24
    textos = [t for t, _ in datos_ent] * REPETICIONES
    etiquetas = [e for _, e in datos_ent] * REPETICIONES
    X_test = [t for t, _ in datos_test]
    y_test = [e for _, e in datos_test]

    print(f"dataset: {len(unicos)} frases unicas | "
          f"prueba {len(X_test)} frases")

    modelo = Pipeline(
        steps=[
            ("tfidf", TfidfVectorizer(
                ngram_range=(1, 2),
                min_df=1,
                sublinear_tf=True,
                strip_accents="unicode",
                lowercase=True,
                stop_words=STOP_WORDS_ES,
            )),
            ("clf", LogisticRegression(max_iter=1000,
                                       class_weight="balanced", C=4.0)),
        ]
    )

    modelo.fit(textos, etiquetas)
    pred = modelo.predict(X_test)

    print(f"\naccuracy : {modelo.score(X_test, y_test):.4f}")
    print(classification_report(y_test, pred, digits=4))

    ruta = os.path.join(MODELOS_DIR, "sentimiento.joblib")
    joblib.dump(modelo, ruta)
    print(f"guardado en {ruta} ({os.path.getsize(ruta)/1024:.0f} KB)")

    return modelo


if __name__ == "__main__":
    os.makedirs(MODELOS_DIR, exist_ok=True)
    t0 = time.perf_counter()
    entrenar_fraude()
    entrenar_sentimiento()
    print(f"\nEntrenamiento completo en {time.perf_counter() - t0:.1f}s")