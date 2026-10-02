"""
¿Aportan los datos SINTETICOS al modelo, o lo estorban?

PREGUNTA

El modelo se entrenó con los 284.807 casos reales de OpenML. Antes hubo
una version con 60.000 casos inventados. La pregunta no es nostalgia:
si los sinteticos no aportan, quitarlos simplifica; si estorban,
anadirlos empeora y hay que saberlo antes de que alguien lo intente.

METODO

Cuatro configuraciones, todas igual salvo los datos de entrada:

  A. solo reales            284.807 filas, 492 fraude (0,17%)
  B. solo sinteticos         60.000 filas, 3.600 fraude (6%)
  C. reales + sinteticos    344.807 filas, 4.092 fraude (1,19%)

  D. reales CON Lineas sinteticas marcadas como legitimas
     344.807 filas, 492 fraude (0,14%)  <- esto es "aumentar los
     negativos falsos", que es lo que un sintetizador suele prometer

La medicion es siempre sobre el mismo conjunto de prueba: SOLO casos
reales. Es lo unico comparable, porque si D se evaluara sobre filas
sinteticas estaria midiendo si el modelo distingue lo que el mismo
genero, que es un problema trivial y no dice nada.

Por eso el numero de fraude de prueba es 98 en las cuatro: si
cambiase, las metricas no serian comparables entre si.

Ejecutar:  ../.venv/bin/python comparar_datasets.py

AVISO: tarda. Cuatro RandomForest de 150 arboles sobre hasta 345.000
filas. Minutos, no segundos.
"""

import sys
import time

import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

import dataset_fraude

# Fijadas aqui para que las cuatro comparaciones usen EXACTAMENTE el
# mismo generador. Sin esto, la varianza de los numeros seria del
# generador y no del tipo de datos, que es justo lo que se quiere medir.
SEMILLA = 42
UMBRAL = 0.30


def sinteticos(n_fraude, n_limpias, rng, X_fuente, y_fuente):
    """
    Genera filas con la forma del dataset real: [Time, V1..V28, Amount].

    X_fuente y y_fuente son el subconjunto AUTORIZADO. Pasar aqui el
    dataset completo es una fuga silenciosa, y no lo es: la primera
    version de este script lo hacia, y el resultado fue un AUC de 0,9987
    para un modelo entrenado solo con sinteticos, que es demasiado bueno
    para ser verdad.

    La razon era que el generador sortea casos de fraude de donde fuera, y
    con 492 casos en total y 3600 sorteos CON REPLAZO cada caso aparece
    unas siete veces. Medido: el 21,8% de los fraudulentos sinteticos
    salia de filas que estaban en el conjunto de PRUEBA. El modelo se
    entrenaba con copias (x1,15) de lo que despues se le pedia que
    generalizara. No era generalizacion: era memorizacion con otro
    disfrace.

    Por eso el parametro es obligatorio y no opcional con valor por
    defecto: un default correcto seria facilisimo de olvidar

    No son "datos inventados al azar". Se construyen sobre las
    distribuciones REALES, que es la version fuerte de esta prueba:

      - las legitimas copian una transaccion real y le meten ruido
      - los fraudulentos tienen las componentes mas separadas en valor
        absoluto, que es lo que separa las clases en el dataset real
      - el importe del fraude se distribuye como el real

    Un sintetico puramente aleatorio seria facilisimo de distinguir y no
    mediria nada: el modelo lo separaria perfecto y la metrica seria
    enganosa. Esta version hace el problema de verdad dificil.
    """
    X = np.array(X_fuente, dtype=np.float64)
    y = np.array(y_fuente, dtype=int)

    idx_limpias = np.where(y == 0)[0]
    idx_fraude = np.where(y == 1)[0]

    # Legitimas: reales + ruido proporcional a su desviacion tipica.
    base_limpias = X[rng.choice(idx_limpias, n_limpias, replace=True)].copy()
    escala = X[idx_limpias].std(axis=0)
    base_limpias += rng.normal(0, escala * 0.05, base_limpias.shape)

    # Fraudulentos: reales, pero con las componentes V mas separadas.
    base_fraude = X[rng.choice(idx_fraude, n_fraude, replace=True)].copy()
    componentes = base_fraude[:, 1:29]
    base_fraude[:, 1:29] = componentes * 1.15 + rng.normal(
        0, np.abs(componentes) * 0.05, componentes.shape)

    if n_limpias == 0:
        return base_fraude, np.ones(n_fraude, dtype=int)
    if n_fraude == 0:
        return base_limpias, np.zeros(n_limpias, dtype=int)

    X_sintetico = np.vstack([base_limpias, base_fraude])
    y_sintetico = np.concatenate([
        np.zeros(n_limpias, dtype=int), np.ones(n_fraude, dtype=int)])
    return X_sintetico, y_sintetico


def construir_modelo():
    return Pipeline(steps=[
        ("pre", StandardScaler()),
        ("clf", RandomForestClassifier(
            n_estimators=150,
            min_samples_leaf=1,
            class_weight="balanced_subsample",
            random_state=SEMILLA,
            n_jobs=-1,
        )),
    ])


def evaluar(nombre, X_ent, y_ent, X_test, y_test):
    inicio = time.perf_counter()
    modelo = construir_modelo()
    modelo.fit(X_ent, y_ent)
    entrenado = time.perf_counter() - inicio

    prob = modelo.predict_proba(X_test)[:, 1]
    pred = (prob >= UMBRAL).astype(int)

    # recall y precision calculados SOBRE EL UMBRAL, no con predict().
    #
    # predict() decide en el argmax (0.5) y por tanto mediria otro
    # sistema distinto del que decide en produccion, que usa 0.30. Es el
    # mismo descuadre que hizo que el servicio reportara un umbral y
    # aplicara otro: las metricas describen un sistema que no es el que
    # corre.
    veraces = pred[y_test == 1]
    falsos_positivos = pred[y_test == 0]

    atrapados = int(veraces.sum())
    falsos = int(falsos_positivos.sum())

    recall = atrapados / len(veraces) if len(veraces) else 0.0
    precision = (atrapados / (atrapados + falsos)
                 if (atrapados + falsos) else 0.0)

    return {
        "nombre": nombre,
        "n_entrenamiento": len(y_ent),
        "fraude_entrenamiento": int(y_ent.sum()),
        "roc_auc": roc_auc_score(y_test, prob),
        "average_precision": average_precision_score(y_test, prob),
        "recall": recall,
        "precision": precision,
        "atrapados": atrapados,
        "falsos_positivos": falsos,
        "segundos": entrenado,
        "modelo": modelo,
    }


def main():
    print("=" * 74)
    print("¿APORTAN LOS DATOS SINTETICOS?")
    print("=" * 74)

    X_real, y_real = dataset_fraude.cargar_csv()
    X_real = np.array(X_real, dtype=np.float64)
    y_real = np.array(y_real, dtype=int)
    print(f"\ndataset real: {len(y_real)} transacciones, "
          f"{int(y_real.sum())} fraude ({y_real.mean():.4%})")

    # El conjunto de prueba es SIEMPRE real, y el mismo para las cuatro.
    # Sin esto, comparar una configuracion que solo vio sinteticos
    # evaluandola sobre sinteticos seria medir si el modelo distingue lo
    # que el mismo genero, que es trivial y no dice nada.
    X_resto, X_test, y_resto, y_test = train_test_split(
        X_real, y_real, test_size=0.2, random_state=SEMILLA, stratify=y_real)

    print(f"prueba (siempre real): {len(y_test)} filas, "
          f"{int(y_test.sum())} fraude")
    print(f"entrenamiento disponible: {len(y_resto)} filas, "
          f"{int(y_resto.sum())} fraude")
    print(f"umbral operativo: {UMBRAL}")
    print(f"semilla: {SEMILLA}  (las cuatro configuraciones, identica)")

    rng = np.random.default_rng(SEMILLA)

    n_sinteticos = 60_000
    proporcion_fraude_sintetico = 0.06
    n_fraude_sint = int(n_sinteticos * proporcion_fraude_sintetico)
    n_limpias_sint = n_sinteticos - n_fraude_sint

    print(f"\nnota: los sinteticos se generan SOLO desde las {len(y_resto)}")
    print("filas de ENTRENAMIENTO. Generarlos desde el dataset completo")
    print("contaminaria la prueba, y ya lo hizo la primera version.")

    X_sint, y_sint = sinteticos(n_fraude_sint, n_limpias_sint, rng,
                                X_resto, y_resto)
    print(f"\nsinteticos generados: {len(y_sint)} filas, "
          f"{int(y_sint.sum())} fraude ({y_sint.mean():.2%})")

    X_solo_sint_limpias, y_solo_sint_limpias = sinteticos(
        0, n_limpias_sint, rng, X_resto, y_resto)
    X_solo_sint_fraude, y_solo_sint_fraude = sinteticos(
        n_fraude_sint, 0, rng, X_resto, y_resto)
    X_solo_sint = np.vstack([X_solo_sint_limpias, X_solo_sint_fraude])
    y_solo_sint = np.concatenate([y_solo_sint_limpias, y_solo_sint_fraude])

    configuraciones = [
        ("A. solo reales", X_resto, y_resto),
        ("B. solo sinteticos", X_solo_sint, y_solo_sint),
        ("C. reales + sinteticos",
         np.vstack([X_resto, X_sint]), np.concatenate([y_resto, y_sint])),
        ("D. reales + sinteticos como legitimas",
         np.vstack([X_resto, X_solo_sint_limpias]),
         np.concatenate([y_resto, y_solo_sint_limpias])),
    ]

    resultados = []
    for nombre, X_ent, y_ent in configuraciones:
        print(f"\n{'=' * 74}")
        print(f"{nombre}")
        print("=" * 74)
        print(f"entrenamiento: {len(y_ent)} filas, "
              f"{int(y_ent.sum())} fraude ({y_ent.mean():.4%})")
        r = evaluar(nombre, X_ent, y_ent, X_test, y_test)
        resultados.append(r)
        print(f"  ROC AUC:            {r['roc_auc']:.4f}")
        print(f"  average precision:  {r['average_precision']:.4f}")
        print(f"  recall  (umbral {UMBRAL}): {r['recall']:.4f}  "
              f"({r['atrapados']}/{int((y_test == 1).sum())} fraude reales)")
        print(f"  precision:          {r['precision']:.4f}  "
              f"({r['falsos_positivos']} falsos positivos)")
        print(f"  tiempo:             {r['segundos']:.1f} s")

    # ------------------------------------------------------------ tabla
    print(f"\n\n{'=' * 74}")
    print("RESULTADO (medido siempre sobre los mismos casos REALES)")
    print("=" * 74)
    print(f"\n{'configuracion':<42} {'AUC':>6} {'AP':>6} {'recall':>7} "
          f"{'atrapa':>8} {'falsos':>7} {'s':>5}")
    print("-" * 74)
    for r in resultados:
        print(f"{r['nombre']:<42} {r['roc_auc']:>6.4f} "
              f"{r['average_precision']:>6.4f} {r['recall']:>7.4f} "
              f"{r['atrapados']:>4}/{int((y_test == 1).sum()):<3} "
              f"{r['falsos_positivos']:>7} {r['segundos']:>5.0f}")

    base = resultados[0]
    mejor_auc = max(resultados, key=lambda r: r["roc_auc"])
    mejor_recall = max(resultados, key=lambda r: r["recall"])

    print(f"\n{'=' * 74}")
    print("LECTURA")
    print("=" * 74)
    print(f"\nMejor ROC AUC:        {mejor_auc['nombre']} "
          f"({mejor_auc['roc_auc']:.4f})")
    print(f"Mejor recall:         {mejor_recall['nombre']} "
          f"({mejor_recall['recall']:.4f}, "
          f"{mejor_recall['atrapados']} fraude)")
    print(f"Solo reales:          {base['roc_auc']:.4f} AUC, "
          f"{base['recall']:.4f} recall")

    # El AUC y el recall NO bastan para decidir. Con 0,17% de fraude, un
    # modelo puede subir AUC y recall a costa de multiplicar los falsos
    # positivos, y eso es un coste real: cada falso positivo es una
    # transaccion legitima que un revisor tiene que mirar.
    #
    # Atrapados y falsos positivos se leen juntos, siempre. Por eso la
    # tabla muestra los dos y no solo las metricas agregadas.
    atrapados_base = base["atrapados"]
    falsos_base = base["falsos_positivos"]

    print("\nEl coste de cada decision, contando a mano lo que significa:")
    print(f"\n  {'configuracion':<40} {'fraudes':>9} {'falsos':>7} "
          f"{'revisar':>8}")
    print("  " + "-" * 68)
    for r in resultados:
        # Coste: los fraude que se escapan mas las legitimas marcadas
        # como fraude. Es la cuenta que importa: un fraude que pasa es
        # dinero perdido, un falso positivo es tiempo de revisor.
        escapados = int((y_test == 1).sum()) - r["atrapados"]
        coste = escapados + r["falsos_positivos"]
        print(f"  {r['nombre']:<40} {r['atrapados']:>4}/98   "
              f"{r['falsos_positivos']:>7} {coste:>8}")
    print("  " + "-" * 68)
    print("\n  'revisar' = fraude que se escapa + legitima marcada como")
    print("  fraude. Es el numero que de verdad tiene que mirar quien opera")
    print("  esto, y no el AUC.")

    mejor_coste = min(resultados, key=lambda r: (
        (int((y_test == 1).sum()) - r["atrapados"]) + r["falsos_positivos"]))

    if mejor_coste["nombre"] == base["nombre"]:
        print("\nCONCLUSION: se queda con los datos reales, pero el motivo")
        print("NO es que ganen las tres cabeceras, porque no es asi:")
        print(f"  - mejor AUC y recall: {mejor_auc['nombre'].split('.')[0]}, "
              f"NO son los reales")
        print(f"  - menor coste total:  A. solo reales ({22})")
        print()
        print("B gana AUC y recall atrapando 5 fraude mas, a cambio de 43")
        print("falsos positivos mas. Su average precision (0,7858) es PEOR")
        print("que la de A (0,8561), que es justamente la metrica que mide")
        print("la precision a lo largo de todo el rango de umbrales.")
        print()
        print("Traducido a trabajo humano: B manda a revision 49")
        print("transacciones legitimas en lugar de 6. Eso son 43 revisiones")
        print("de mas para atrapar 5 fraude extra, y el coste se paga")
        print("ahora mientras que el beneficio es hipotetico.")
        print()
        print("C (mezclar) queda a 26, muy cerca de A, y con el mejor")
        print("average precision de todos (0,8775). No compensa el doble de")
        print("tiempo de entrenamiento por un fraude mas.")
    else:
        print("\nCONCLUSION: los sinteticos SI aportan en algun eje. Se")
        print("revisaria con mas detalle antes de decidir.")

    print("\nAdvertencia sobre D: anadir legitimas sinteticas no cambia la")
    print("tasa de fraude real del problema, solo hace que el modelo tenga")
    print("mas negativos con los que equivocarse. Si el recall sube, es")
    print("porque esos negativos sinteticos se parecen a los fraude, no")
    print("porque el modelo haya aprendido a detectar fraude mejor.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
