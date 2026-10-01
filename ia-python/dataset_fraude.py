"""
Lee el dataset real de fraude de OpenML (creditcard, did 1597).

285.000 transacciones de tarjeteros europeos, con 492 casos de fraude
etiquetados. Las variables V1-V28 vienen anonimizadas por PCA; solo
"Time" (segundos desde la primera transaccion) y "Amount" son legibles.

Se eligió este dataset por una razón práctica: es público, se puede
descargar sin autenticación y es el estándar de referencia para este
problema. Aun así tiene una limitación importante que conviene decir:
las columnas V1..V28 NO se pueden interpretar. Para este proyecto eso
no importa, porque el modelo aprende la relación entre esas señales y
la etiqueta, no qué significa cada una. Pero sí impide razonar sobre
por qué una transacción es fraude, que es lo que un detector de fraude
real necesita.

Fuente: https://www.openml.org/d/1597
"""

import csv
import os
import random
import sys
import urllib.request

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
RUTA_ARFF = os.path.join(BASE_DIR, "datos", "creditcard.arff")
RUTA_CSV = os.path.join(BASE_DIR, "datos", "creditcard.csv")

URL = "https://www.openml.org/data/v1/download/1673544/creditcard.arff"

# Variables de una transaccion: Time + V1..V28 + Amount = 30 columnas.
# El orden importa y lo fijamos aqui explicitamente.
COLUMNAS = ["Time"] + [f"V{i}" for i in range(1, 29)] + ["Amount"]

NOMBRE_CLASE = "class"


def _quitar_comillas(valor: str) -> str:
    return valor.strip().strip("'").strip('"')


def leer_arff(ruta: str):
    """Devuelve (cabecera, filas) leyendo la seccion @data del ARFF."""
    filas = []
    en_datos = False
    cabecera = None

    with open(ruta, encoding="utf-8") as f:
        for linea in f:
            linea = linea.strip()
            if not linea:
                continue

            if not en_datos:
                if linea.lower().startswith("@attribute"):
                    if cabecera is None:
                        cabecera = []
                    cabecera.append(_quitar_comillas(linea.split()[1]))
                elif linea.lower().startswith("@data"):
                    en_datos = True
                continue

            # Formato ARFF: valores separados por coma, la clase entrecomillada.
            partes = linea.split(",")
            filas.append([_quitar_comillas(p) for p in partes])

    return cabecera, filas


def _verificar_cabecera(cabecera):
    """
    Comprueba que la cabecera del ARFF es la que COLUMNAS espera.

    Existe por un fallo silencioso real: las filas del ARFF se leen por
    POSICION y antes se escribian con una cabecera inventada (COLUMNAS)
    sin mirar la del fichero. Si el dataset se publicara reordenado, cada
    valor recibiria la etiqueta de su vecino, el modelo se entrenaria con
    senas mal nombradas y las metricas publicadas pasarian a describir otro
    sistema. Sin una excepcion, sin un aviso: solo un numero.

    El riesgo no es teorico porque datos/ esta en .gitignore: cada
    desarrollador y cada CI descargan el fichero de OpenML, asi que es el
    dataset que se acaba usando, no el que se probo.
    """
    if cabecera is None:
        raise ValueError(
            f"{RUTA_ARFF} no tiene cabecera de atributos. ¿Es un ARFF?")

    esperada = [c.lower() for c in COLUMNAS + [NOMBRE_CLASE]]
    recibida = [c.lower() for c in cabecera]

    if recibida != esperada:
        # Se informa de la primera diferencia y de cuantas hay: un
        # "no coincide" sin mas datos obliga a abrir el fichero a mano.
        diferencias = [
            f"posicion {i}: esperaba {e}, leí {r}"
            for i, (e, r) in enumerate(zip(esperada, recibida)) if e != r
        ]
        raise ValueError(
            "La cabecera del dataset no coincide con la que espera el "
            f"entrenamiento.\n"
            f"  esperada ({len(esperada)}): {esperada}\n"
            f"  recibida ({len(recibida)}): {recibida}\n"
            + "\n".join(f"  {d}" for d in diferencias)
            + "\nEntrenar con esto reetiquetaria las columnas en silencio. "
              "Se detiene aqui a proposito."
        )


def cargar_csv(ruta: str = RUTA_CSV):
    """Carga el dataset ya convertido a CSV. Cachea la conversion."""
    if not os.path.exists(ruta):
        if not os.path.exists(RUTA_ARFF):
            raise FileNotFoundError(
                f"No se encuentra el dataset en {RUTA_ARFF}.\n"
                "Descargalo con:\n"
                "  mkdir -p datos && curl -L "
                "https://www.openml.org/data/v1/download/1673544/creditcard.arff "
                "-o datos/creditcard.arff"
            )

        os.makedirs(os.path.dirname(ruta), exist_ok=True)
        cabecera, filas = leer_arff(RUTA_ARFF)
        _verificar_cabecera(cabecera)

        with open(ruta, "w", newline="", encoding="utf-8") as f:
            escritor = csv.writer(f)
            escritor.writerow(cabecera)
            escritor.writerows(filas)

    with open(ruta, encoding="utf-8") as f:
        lector = csv.DictReader(f)
        X, y = [], []
        for fila in lector:
            X.append([float(fila[c]) for c in COLUMNAS])
            y.append(int(fila[NOMBRE_CLASE]))

    return X, y


def _resumen(X, y):
    """Imprime el recuento y las estadisticas de las dos variables legibles."""
    fraude = sum(y)
    print(f"transacciones: {len(X)}")
    print(f"variables:     {len(COLUMNAS)}")
    print(f"fraude:        {fraude} ({fraude/len(y):.4%})")
    print(f"legitimas:     {len(y) - fraude}")

    import statistics

    i_time = COLUMNAS.index("Time")
    i_amount = COLUMNAS.index("Amount")

    for etiqueta in (0, 1):
        filas = [x for x, c in zip(X, y) if c == etiqueta]
        if not filas:
            continue
        montos = [x[i_amount] for x in filas]
        tiempos = [x[i_time] for x in filas]
        print(f"\nclase {etiqueta} ({'fraude' if etiqueta else 'legitima'}, "
              f"n={len(filas)}):")
        print(f"  monto  mediana: {statistics.median(montos):8.2f}  "
              f"media: {statistics.mean(montos):8.2f}")
        print(f"  tiempo mediana: {statistics.median(tiempos):8.0f}s")


def muestra(X, y, n, semilla=42):
    """
    Subconjunto ESTRATIFICADO de n transacciones.

    Existe para el CI, no para producir el modelo publicado.

    Bajar los 144 MB en cada ejecucion de CI es lo que haria que el job
    tardara mas que los propios tests. Pero reducir el dataset tomando las
    primeras n filas NO vale: en este dataset el fraude esta repartido por
    todo el fichero, asi que una muestra por cabeza puede salir con cero
    casos de fraude y el entrenamiento no tendria nada que aprender.

    Por eso se conservan TODOS los casos de fraude y se completan con
    legitimas elegidas al azar. La proporcion de fraude de la resultante no
    es la real, y eso es deliberado: el modelo de este subset no es el que
    se publica, es un modelo de pruebas que solo tiene que existir para que
    los tests puedan cargar algo.

    Para la metrica real, el dataset entero.
    """
    if n >= len(y):
        return X, y

    rng = random.Random(semilla)
    indices_fraude = [i for i, c in enumerate(y) if c == 1]
    legitimas = [i for i, c in enumerate(y) if c == 0]

    elegidos = list(indices_fraude)
    restantes = max(n - len(elegidos), 0)
    elegidos += rng.sample(legitimas, min(restantes, len(legitimas)))
    elegidos.sort()

    return [X[i] for i in elegidos], [y[i] for i in elegidos]


def _main():
    import argparse

    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--muestra", type=int, metavar="N",
                   help="entrenar sobre N transacciones (para CI)")
    args = p.parse_args()

    X, y = cargar_csv()

    if args.muestra:
        print(f"AVISO: muestra de {args.muestra} transacciones. Las "
              "metricas NO son las publicadas en el README.\n")
        X, y = muestra(X, y, args.muestra)

    _resumen(X, y)
    return 0


if __name__ == "__main__":
    sys.exit(_main())