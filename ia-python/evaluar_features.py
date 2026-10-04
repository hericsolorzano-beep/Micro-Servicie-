"""
¿Ayudan las variables derivadas de hora e importe?

Este script responde a una pregunta concreta: el modelo de fraude ve 30
columnas (Time, V1..V28, Amount) y deja escapar 16 de cada 98 fraudes de
prueba. ¿Anadir variables derivadas mejora ese recall?

EJECUTAR:  ../.venv/bin/python evaluar_features.py

LA TRAMPA QUE ESTE SCRIPT EVITA
--------------------------------

Al medir, la fila de Time se sustituye por hora*3600 ANTES de puntuar,
igual que hace main.py en inferencia. No es un detalle: si se midiera con
el Time real, las metricas describirian un sistema que no es el que corre
en produccion.

La razon es que el dataset da Time en "segundos desde la primera
transaccion" y abarca unos dos dias (0 a ~172.800). El servicio, en
cambio, solo recibe "hora" (0-23) y reconstruye Time como hora*3600, es
decir, entre 0 y 82.800. Son la misma magnitud en forma distinta, pero
no el mismo valor, y una columna de la que el modelo aprendio una
relacion con la media de Time no recibe lo que aprendio.

Sin esta sustitucion, cualquier conclusion sobre features de tiempo seria
falsa. Con ella, es fea pero real.

QUE FEATURES SE PRUEBAN Y POR QUE ESTAS
---------------------------------------

Se derivan SEIS, no nueve. La razon de dejar fuera hora, dia y
fin_de_semana es que NO son consistentes entre entrenamiento e
inferencia:

  hora          = (Time % 86400) / 3600   -> se puede reconstruir igual
  sin_hora      = sin(2*pi*hora/24)       -> se puede reconstruir igual
  cos_hora      = cos(2*pi*hora/24)       -> se puede reconstruir igual
  es_noche      = hora < 6 o hora >= 22   -> se puede reconstruir igual
  log_importe   = log1p(Amount)           -> se puede reconstruir igual
  z_importe     = (Amount - media)/desv   -> se puede reconstruir igual
  es_atipico    = |z_importe| > 3         -> se puede reconstruir igual

  hora en crudo -> se puede, pero como variable lineal mete una
                   discontinuidad a medianoche: las 23:59 y las 00:00
                   son casi identicas y el modelo las trataria como
                   opuestas. Sin y cos dicen lo mismo sin cortar.
  dia           = (Time // 86400) % 7     -> EN INFERENCIA SIEMPRE 0,
                   porque Time = hora*3600 nunca pasa de 86400. Una
                   feature que en produccion es una constante no aporta
                   nada: el StandardScaler la habria centrado en 0 y el
                   modelo veria siempre el mismo valor.
  fin_de_semana -> igual que dia: sale de Time, y en inferencia es
                   siempre 0. Mismo problema.

Añadir dia y fin_de_semana habria inflado el numero de features y
habria hecho el modelo MAS PEOR en produccion, no mejor: el
entrenamiento las veria variar y el servicio les daria siempre 0. Esa
es una forma de fuga que no aparece en ninguna metrica de este script,
porque solo se manifiesta al servir, y por eso se descarta antes de
medir y no despues.
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

SEMILLA = 42
UMBRAL = 0.30

# Indice de cada columna en la fila original de 30.
IDX_TIME = 0
IDX_AMOUNT = 29


def hora_del_dia(time_col):
    """Hora del dia (0-23, fraccionaria) a partir de Time en segundos."""
    return (time_col % 86400.0) / 3600.0


def features_base(X):
    """Las 30 columnas originales, sin tocar."""
    return np.asarray(X, dtype=np.float64)


def features_time_servido(X, media_importe, desv_importe):
    """
    Las 30 columnas, pero con Time ya sustituido como en el servicio.

    Devuelve ademas las seis features derivadas, ya calculadas sobre la
    hora RECONSTRUIDA, que es la unica que existe en inferencia.
    """
    X = np.array(X, dtype=np.float64, copy=True)
    hora = hora_del_dia(X[:, IDX_TIME])
    X[:, IDX_TIME] = hora * 3600.0          # lo que hace main.py

    importe = X[:, IDX_AMOUNT]
    z = (importe - media_importe) / desv_importe

    derivadas = np.column_stack([
        np.sin(2.0 * np.pi * hora / 24.0),
        np.cos(2.0 * np.pi * hora / 24.0),
        ((hora < 6.0) | (hora >= 22.0)).astype(np.float64),
        np.log1p(importe),
        z,
        (np.abs(z) > 3.0).astype(np.float64),
    ])
    return X, derivadas


def construir():
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


def medir(nombre, X_ent, y_ent, X_prueba, y_prueba):
    t0 = time.perf_counter()
    m = construir()
    m.fit(X_ent, y_ent)
    prob = m.predict_proba(X_prueba)[:, 1]

    pos = y_prueba == 1
    atrapa = int((prob[pos] >= UMBRAL).sum())
    falsos = int((prob[~pos] >= UMBRAL).sum())
    escapan = int(pos.sum()) - atrapa
    coste = escapan + falsos

    r = {
        "nombre": nombre,
        "columnas": X_ent.shape[1],
        "auc": roc_auc_score(y_prueba, prob),
        "ap": average_precision_score(y_prueba, prob),
        "atrapa": atrapa,
        "escapan": escapan,
        "falsos": falsos,
        "coste": coste,
        "prob": prob,
        "pos": pos,
        "seg": time.perf_counter() - t0,
    }
    print(f"  columnas {r['columnas']:>2} | AUC {r['auc']:.4f} | "
          f"AP {r['ap']:.4f} | atrapa {atrapa}/{int(pos.sum())} | "
          f"escapan {escapan} | falsos {falsos} | coste {coste} | "
          f"{r['seg']:.0f}s")
    return r


def main():
    print("=" * 74)
    print("¿AYUDAN LAS VARIABLES DERIVADAS? (con Time como en el servicio)")
    print("=" * 74)

    X, y = dataset_fraude.cargar_csv()
    X = np.array(X, dtype=np.float64)
    y = np.array(y, dtype=int)
    print(f"\ndataset: {len(y)} filas, {int(y.sum())} fraude "
          f"({y.mean():.4%})")

    X_resto, X_prueba, y_resto, y_prueba = train_test_split(
        X, y, test_size=0.2, random_state=SEMILLA, stratify=y)
    print(f"prueba:  {len(y_prueba)} filas, {int(y_prueba.sum())} fraude")

    # La media y desviacion del importe se SACAN SOLO del entrenamiento.
    # Usar las de toda la base seria mirar la prueba al construir el
    # modelo, que es una fuga pequena pero real.
    media = float(X_resto[:, IDX_AMOUNT].mean())
    desv = float(X_resto[:, IDX_AMOUNT].std()) + 1e-12
    print(f"importe (del entrenamiento): media {media:.2f}, desv {desv:.2f}")

    Xt_resto, der_resto = features_time_servido(X_resto, media, desv)
    Xt_prueba, der_prueba = features_time_servido(X_prueba, media, desv)

    print("\n1) REFERENCIA: como esta HOY el codigo")
    print("   (30 columnas, Time real en entrenamiento y en prueba)")
    ref = medir("referencia", features_base(X_resto), y_resto,
                features_base(X_prueba), y_prueba)

    print("\n2) REFERENCIA HONESTA: 30 columnas pero con Time ya servido")
    print("   (es lo que hace main.py: Time = hora*3600)")
    servido = medir("servido", Xt_resto, y_resto, Xt_prueba, y_prueba)

    print("\n3) CON LAS SEIS DERIVADAS (36 columnas), mismo Time servido")
    ext_resto = np.column_stack([Xt_resto, der_resto])
    ext_prueba = np.column_stack([Xt_prueba, der_prueba])
    ext = medir("extendido", ext_resto, y_resto, ext_prueba, y_prueba)

    # Cuantos de los que escapaba la version de 36 recuperan.
    esc_serv = servido["pos"] & (servido["prob"] < UMBRAL)
    esc_ext = ext["pos"] & (ext["prob"] < UMBRAL)
    print(f"\n  escapaban (30 col., Time servido): {int(esc_serv.sum())}")
    print(f"  escapan   (36 col.):                {int(esc_ext.sum())}")
    print(f"  RECUPERADOS:                        "
          f"{int((esc_serv & ~esc_ext).sum())}")

    print(f"\n{'configuracion':<12} {'col':>4} {'AUC':>7} {'AP':>7} "
          f"{'atrapa':>8} {'falsos':>7} {'coste':>6}")
    print("-" * 60)
    for r in (ref, servido, ext):
        print(f"{r['nombre']:<12} {r['columnas']:>4} {r['auc']:>7.4f} "
              f"{r['ap']:>7.4f} {r['atrapa']:>4}/{int(r['pos'].sum()):<3} "
              f"{r['falsos']:>7} {r['coste']:>6}")

    print("\n" + "=" * 74)
    print("LECTURA")
    print("=" * 74)
    print(f"\nEl cambio minimo e inevitable es el 2: main.py ya construia")
    print(f"Time = hora*3600 y el entrenamiento no lo tenia en cuenta.")
    print(f"Su coste real es {servido['coste']}, no {ref['coste']}.")
    if ext["coste"] <= servido["coste"] and ext["atrapa"] >= servido["atrapa"]:
        print(f"\nLas seis derivadas SI ajudam: atrapan "
              f"{ext['atrapa'] - servido['atrapa']} mas, coste "
              f"{servido['coste']} -> {ext['coste']}.")
    elif ext["coste"] < servido["coste"]:
        print(f"\nLas seis derivadas reducen el coste "
              f"({servido['coste']} -> {ext['coste']}) pero atrapan "
              f"{ext['atrapa'] - servido['atrapa']} menos.")
    else:
        print(f"\nLas seis derivadas NO mejoran: coste "
              f"{servido['coste']} -> {ext['coste']}, atrapan "
              f"{ext['atrapa']} frente a {servido['atrapa']}.")
        print("En ese caso la decision correcta es NO añadirlas.")

    print(f"\nRecordatorio de la escala: el prueba tiene "
          f"{int(y_prueba.sum())} casos de fraude.")
    print("Un solo caso mueve el recall un punto entero, asi que una")
    print("diferencia de 1 o 2 casos entre filas contiguas es ruido.")
    return 0


if __name__ == "__main__":
    sys.exit(main())