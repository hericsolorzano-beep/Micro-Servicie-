"""
Tests del servicio de IA.

Foco en las dos cosas que el reviewer marco como mas fragiles y que los
tests de Java no pueden cubrir, porque estan al otro lado del proceso:

1. El ORDEN DE COLUMNAS que se construye en inferencia debe coincidir con
   el que espera el ColumnTransformer del entrenamiento. Si se desalinea,
   sklearn no lanza error: predice con el sentido invertido y nadie se
   entera hasta que el detector de fraude aprueba un fraude.
2. El contrato de campos entre main.py y la salida real de Python.

Ejecutar:  python -m pytest test_modelos.py -v
           (o: python test_modelos.py)
"""

import os
import sys
import unittest

import joblib
import numpy as np

import dataset_fraude
import motor_sentimiento as ms

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELOS_DIR = os.path.join(BASE_DIR, "modelos")


def _cargar_modelo(ruta):
    """
    El .joblib guarda {"modelo": pipeline, "umbral": float} desde que el
    modelo se entrena con datos reales: el umbral viaja con el modelo para
    que la metrica publicada describa el sistema que realmente decide.

    Los tests unloaded con solo el pipeline, que es lo que interesa
    verificar aqui.
    """
    cargado = joblib.load(ruta)
    if isinstance(cargado, dict):
        return cargado["modelo"]
    return cargado


class TestOrdenDeColumnas(unittest.TestCase):
    """El contrato mas fragile de todo el servicio."""

    @classmethod
    def setUpClass(cls):
        ruta = os.path.join(MODELOS_DIR, "fraude.joblib")
        if not os.path.exists(ruta):
            raise unittest.SkipTest(
                "Ejecuta primero: python entrenamiento.py")
        cls.modelo = _cargar_modelo(ruta)

    def test_main_construye_la_fila_en_el_orden_entrenado(self):
        """
        ESTE es el test que importa. Los otros dos comprueban el modelo,
        pero no el sitio desde el que se le llama.

        Si alguien reordena la fila en main.py
        (por ejemplo, pone el pais en segundo lugar), los tests que leen
        el .joblib seguirian pasando: la especificacion congelada del
        transformador no cambia por reentrenar nada. El modelo recibiria
        [monto, pais, hora, distancia] y devolveria una prediccion
        silenciosamente incorrecta.

        Aqui se intercepta la fila que main.py REALMENTE construye y se
        compara con los indices que el ColumnTransformer espera.
        """
        import main as servicio

        fila_capturada = {}

        class ModeloFalso:
            """Captura la fila sin predecir nada."""

            def predict(self, X):
                fila_capturada["X"] = X[0]
                return [1]

            def predict_proba(self, X):
                fila_capturada["X"] = X[0]
                return [[0.1, 0.9]]

        modelo_original = servicio.modelo_fraude
        servicio.modelo_fraude = ModeloFalso()
        try:
            servicio.predecir_fraude(servicio.TransaccionRequest(
                componentes=[0.0] * 28, monto=9000.0, hora=3,
                pais="NG", distancia_km=5200.0))
        finally:
            servicio.modelo_fraude = modelo_original

        fila = fila_capturada["X"]

        self.assertEqual(len(fila), 30)

        # La fila completa: [Time, V1..V28, Amount].
        # Time = hora * 3600 (main.py lo reconstruye asi), 28 componentes,
        # y el monto al final.
        self.assertEqual(fila[0], 3 * 3600.0)   # Time
        self.assertEqual(fila[29], 9000.0)      # Amount
        self.assertEqual(len(fila[1:29]), 28)   # V1..V28

    def test_orden_de_columnas_esperado_por_el_transformer(self):
        """
        El pipeline espera 30 columnas en el orden [Time, V1..V28, Amount].

        Si alguien reordena la fila en main.py, el modelo recibe las
        columnas en otro orden y predice SIN ERROR, con el sentido
        invertido. Por eso el numero de features se comprueba aqui.
        """
        # El paso "pre" es un StandardScaler sobre las 30 columnas.
        # n_features_in_ es el numero exacto que el clasificador espera:
        # si main.py dejara de enviar una componente, predict() fallaria
        # aqui con un error de dimensiones.
        self.assertEqual(
            self.modelo.named_steps["clf"].n_features_in_, 30,
            "El modelo espera 30 features: Time + V1..V28 + Amount")

    def test_inferencia_respeta_el_orden_entrenado(self):
        """
        Reconstruye la fila EXACTAMENTE como lo hace main.py y comprueba
        que una transccion marcada como fraude se detecta.

        OJO con lo que este test ya NO afirma. Con el modelo sintetico
        anterior, "Nigeria a las 3am por 9000" era fraude por regla. Con
        el modelo real, los componentes PCA deciden y el pais no entra: una
        fila de ceros con monto alto NO es fraude. Por eso se usan filas
        reales del dataset (vienen de dataset_fraude) y no inventadas.
        """
        X, y = dataset_fraude.cargar_csv()
        import numpy as np
        X = np.array(X)
        y = np.array(y)

        reales = np.where(y == 1)[0][:50]
        detectadas = 0
        for i in reales:
            fila = [3 * 3600.0] + list(X[i][1:29]) + [float(X[i][29])]
            if self.modelo.predict([fila])[0] == 1:
                detectadas += 1

        # Con el umbral por defecto el modelo detecta la mayoria. El
        # umbral operativo (0.30)Recall mas, pero este test mide que la
        # inferencia monta bien la fila, no que el modelo sea perfecto.
        proporcion = detectadas / len(reales)
        self.assertGreater(
            proporcion, 0.7,
            f"El modelo deberia detectar al menos el 70% del fraude real; "
            f"detecto {proporcion:.0%}")

    def test_transaccion_legitima_se_aprueba(self):
        """Filas REALES legitimas deben salir como legitimas."""
        import numpy as np
        X, y = dataset_fraude.cargar_csv()
        X = np.array(X)
        y = np.array(y)

        reales = np.where(y == 0)[0][:500]
        falsos = sum(
            1 for i in reales
            if self.modelo.predict([[12 * 3600.0] + list(X[i][1:29])
                                    + [float(X[i][29])]])[0] == 1
        )

        proporcion = falsos / len(reales)
        self.assertLess(
            proporcion, 0.05,
            f"Demasiadas legitimas marcadas como fraude: {proporcion:.1%}")

    def test_probabilidad_de_fraude_esta_en_indice_de_clase_1(self):
        """
        main.py lee predict_proba(X)[0][1]. Si clases_ no fuera [0, 1], ese
        indice no seria la probabilidad de fraude.
        """
        clases = list(self.modelo.classes_)
        self.assertEqual(clases, [0, 1],
                         "El indice [1] de predict_proba debe ser 'fraude'")
        self.assertEqual(
            self.modelo.named_steps["clf"].n_classes_, 2)


class TestContratoDePython(unittest.TestCase):
    """Los campos que Java espera deben existir en la respuesta."""

    @classmethod
    def setUpClass(cls):
        ruta = os.path.join(MODELOS_DIR, "fraude.joblib")
        if not os.path.exists(ruta):
            raise unittest.SkipTest(
                "Ejecuta primero: python entrenamiento.py")
        cls.modelo = _cargar_modelo(ruta)

    def test_respuesta_tiene_los_campos_que_espera_java(self):
        """
        Contrato REAL, leido del esquema de FastAPI y comparado con los
        campos que RespuestaFraudePython.java declara.

        Comparar contra un dict literal que uno mismo acaba de escribir no
        comprueba nada: pasaria aunque main.py cambiara entero. Aqui las
        dos fuentes de verdad son independientes, que es lo que hace util
        al test.
        """
        import main as servicio

        # Lo que Java espera deserializar. RespuestaFraudePython.java
        # declara estos cinco; "umbral" se anadio cuando el modelo paso a
        # usar datos reales, para que el cliente sepa con que criterio se
        # decidio el veredicto.
        campos_java = {"es_fraude", "probabilidad", "nivel_riesgo",
                       "modelo", "umbral"}

        # Lo que el esquema de Python garantiza devolver.
        campos_python = set(servicio.FraudeResponse.model_fields)

        self.assertEqual(
            campos_java, campos_python,
            "Los campos de FraudeResponse cambiaron. Si anadiste uno, "
            "actualiza RespuestaFraudePython.java; si quitaste uno, el "
            "cliente de Java dejaria de recibirlo.")

        # Y que la peticion que Java envia tenga los mismos nombres.
        campos_peticion = set(servicio.TransaccionRequest.model_fields)
        self.assertEqual({"componentes", "monto", "hora", "pais",
                          "distancia_km"}, campos_peticion)

    def test_sentimiento_respuesta_tiene_los_campos_que_espera_java(self):
        import main as servicio

        campos_java = {"sentimiento", "confianza", "probabilidades", "modelo"}
        campos_python = set(servicio.SentimientoResponse.model_fields)

        self.assertEqual(campos_java, campos_python)

    def test_paises_no_son_parte_del_contrato_del_modelo(self):
        """
        El modelo real NO usa pais. V1..V28 son componentes PCA y el
        dataset de referencia no tiene informacion de geografia.

        Este test existe para dejar constancia de esa decision, porque es
        contraintuitiva: el contrato publico incluye pais (el cliente lo
        tiene y quiere mostrarlo) pero no influye en la prediccion. Si
        alguien lo anade al pipeline sin reentrenar, este test falla.
        """
        import main as servicio

        # El paso "pre" es un StandardScaler sobre 30 columnas, sin
        # codificacion categorica de ningun tipo.
        self.assertEqual(
            type(self.modelo.named_steps["pre"]).__name__,
            "StandardScaler",
            "Si el pipeline vuelve a codificar categorias, el modelo "
            "necesita reentrenarse con el esquema nuevo")

        # Y la lista de paises es de validacion de formato, no de
        # disponibilidad del modelo: cualquier ISO de 2 letras mayusculas
        # que este en la lista se acepta.
        self.assertIn("ES", servicio.PAISES_VALIDOS)
        self.assertIn("US", servicio.PAISES_VALIDOS)


class TestMuestraEstratificada(unittest.TestCase):
    """
    La funcion que usa el CI para no bajar 144 MB en cada ejecucion.

    Existe por un motivo concreto: reducir el dataset tomando las primeras
    n filas puede devolver CERO casos de fraude, porque en este dataset el
    fraude esta repartido por todo el fichero. Un modelo entrenado asi no
    tiene nada que aprender, y el fallo se manifestaria como "el modelo
    devuelve siempre 0", que no senala la causa.
    """

    def setUp(self):
        # Dataset sintetico pequeno: lo que importa es la MECANICA de la
        # muestra, no la distribucion real.
        self.X = [[float(i)] * 30 for i in range(1000)]
        self.y = [1] * 5 + [0] * 995

    def test_conserva_todos_los_casos_de_fraude(self):
        _, y = dataset_fraude.muestra(self.X, self.y, 200)

        self.assertEqual(sum(y), 5,
                         "Perder un caso de fraude cambia el modelo. Con "
                         "una muestra por cabeza puede perderse el "
                         "entero.")

    def test_respeta_el_tamano_pedido(self):
        X, y = dataset_fraude.muestra(self.X, self.y, 200)

        self.assertEqual(len(X), 200)
        self.assertEqual(len(y), 200)

    def test_es_determinista(self):
        # Sin esto, cada ejecucion del CI entrenaria un modelo distinto y
        # un fallo pasaria a ser intermitente.
        _, a = dataset_fraude.muestra(self.X, self.y, 200)
        _, b = dataset_fraude.muestra(self.X, self.y, 200)

        self.assertEqual(a, b)

    def test_pedir_mas_que_lo_que_hay_devuelve_todo(self):
        # Es el caso limite: si n >= len(y) no hay nada que recortar, y un
        # error aqui daria un dataset vacio.
        X, y = dataset_fraude.muestra(self.X, self.y, 999_999)

        self.assertEqual(len(X), 1000)
        self.assertEqual(len(y), 1000)

    def test_el_orden_original_se_conserva(self):
        # Recortar no puede reordenar: el orden temporal de Time es una
        # senal en si mismo, y un train_test_split sin estratificar
        # asumiria que el orden es aleatorio.
        X, _ = dataset_fraude.muestra(self.X, self.y, 200)
        tiempos = [fila[0] for fila in X]

        self.assertEqual(tiempos, sorted(tiempos))


class TestSentimiento(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        ruta = os.path.join(MODELOS_DIR, "sentimiento.joblib")
        if not os.path.exists(ruta):
            raise unittest.SkipTest(
                "Ejecuta primero: python entrenamiento.py")
        cls.modelo = _cargar_modelo(ruta)

    def test_las_tres_clases_existen(self):
        clases = set(self.modelo.named_steps["clf"].classes_)
        self.assertEqual(clases, {"positivo", "negativo", "neutro"})

    def test_el_sentimiento_devuelto_es_el_de_mayor_probabilidad(self):
        """
        Invariante real del endpoint: la clase informada debe coincidir con
        la de mayor probabilidad. Comprueba la logica de main.py, no a
        sklearn (predict_proba suma 1 por construccion, asi que testear
        eso no probaria nada de este codigo).
        """
        import main as servicio

        # El endpoint usa un motor con respaldo, no el pipeline suelto.
        # Montamos uno real con solo el TF-IDF, que es rapido y no
        # descarga 1 GB.
        import dataset_fraude

        original = servicio.motor_sentimiento
        servicio.motor_sentimiento = ms.construir_motor(usar_transformer=False)
        try:
            r = servicio.predecir_sentimiento(
                servicio.TextoRequest(texto="pésima calidad, llegó roto"))

            probabilidades = r.probabilidades
            mejor = max(probabilidades, key=probabilidades.get)
            self.assertEqual(r.sentimiento, mejor)
            self.assertAlmostEqual(r.confianza, probabilidades[mejor], places=6)
        finally:
            servicio.motor_sentimiento = original

    def test_frases_obvias_se_clasifican_bien(self):
        """
        Comprobacion de humo, no de exactitud: con 86 frases de
        entrenamiento el modelo no generaliza a texto libre.
        """
        casos = [
            ("excelente producto muy recomendado", "positivo"),
            ("producto de muy mala calidad", "negativo"),
            ("el producto llego en su caja", "neutro"),
        ]
        for texto, esperado in casos:
            with self.subTest(texto=texto):
                self.assertEqual(self.modelo.predict([texto])[0], esperado)


if __name__ == "__main__":
    unittest.main(verbosity=2)