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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MODELOS_DIR = os.path.join(BASE_DIR, "modelos")


class TestOrdenDeColumnas(unittest.TestCase):
    """El contrato mas fragile de todo el servicio."""

    @classmethod
    def setUpClass(cls):
        ruta = os.path.join(MODELOS_DIR, "fraude.joblib")
        if not os.path.exists(ruta):
            raise unittest.SkipTest(
                "Ejecuta primero: python entrenamiento.py")
        cls.modelo = joblib.load(ruta)

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
                monto=9000.0, hora=3, pais="NG", distancia_km=5200.0))
        finally:
            servicio.modelo_fraude = modelo_original

        fila = fila_capturada["X"]

        # ColumnTransformer fue fitted con num -> [0, 1, 3] y pais -> [2].
        # Reconstruimos a mano lo que el pipeline hace con la fila y
        # comprobamos que monto, hora y distancia caen en las columnas
        # numericas y el pais en la de pais.
        transformadores = {n: c for n, _, c
                           in self.modelo.named_steps["pre"].transformers}
        self.assertEqual(transformadores["num"], [0, 1, 3])
        self.assertEqual(transformadores["pais"], [2])

        # La fila de inferencia debe ser [monto, hora, pais, distancia]
        self.assertEqual(fila, [9000.0, 3, "NG", 5200.0])

        # Y los valores deben caer donde el transformador los espera:
        # numericas en 0, 1 y 3; pais en 2.
        self.assertEqual(fila[0], 9000.0)   # monto
        self.assertEqual(fila[1], 3)        # hora
        self.assertEqual(fila[2], "NG")     # pais
        self.assertEqual(fila[3], 5200.0)   # distancia_km

    def test_orden_de_columnas_esperado_por_el_transformer(self):
        """
        ColumnTransformer fue fitted con num -> [0, 1, 3] y pais -> [2].
        Si alguien reordena las columnas en main.py sin reentrenar, el
        modelo recibe [monto, pais, hora, distancia] y predice mal en
        silencio. Este test falla si el mapeo esperado cambia.
        """
        transformadores = {nombre: cols for nombre, _, cols
                           in self.modelo.named_steps["pre"].transformers}

        self.assertEqual(transformadores["num"], [0, 1, 3])
        self.assertEqual(transformadores["pais"], [2])

    def test_inferencia_respeta_el_orden_entrenado(self):
        """
        Reconstruye la fila EXACTAMENTE como lo hace main.py y comprueba
        que una transaccion claramente sospechosa se detecta.
        """
        fila = [9000.0, 3, "NG", 5200.0]  # monto, hora, pais, distancia
        prediccion = self.modelo.predict([fila])[0]

        self.assertEqual(
            prediccion, 1,
            "Una transaccion de Nigeria a las 3am por 9000 deberia ser fraude")

    def test_transaccion_legitima_se_aprueba(self):
        fila = [150.0, 12, "ES", 80.0]
        prediccion = self.modelo.predict([fila])[0]

        self.assertEqual(
            prediccion, 0,
            "Una compra pequena en Espana a mediodia no deberia ser fraude")

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
        cls.modelo = joblib.load(ruta)

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

        # Lo que Java espera deserializar.
        campos_java = {"es_fraude", "probabilidad", "nivel_riesgo", "modelo"}

        # Lo que el esquema de Python garantiza devolver.
        campos_python = set(servicio.FraudeResponse.model_fields)

        self.assertEqual(
            campos_java, campos_python,
            "Los campos de FraudeResponse cambiaron. Si anadiste uno, "
            "actualiza RespuestaFraudePython.java; si quitaste uno, el "
            "cliente de Java dejaria de recibirlo.")

        # Y que la peticion que Java envia tenga los mismos nombres.
        campos_peticion = set(servicio.TransaccionRequest.model_fields)
        self.assertEqual({"monto", "hora", "pais", "distancia_km"},
                         campos_peticion)

    def test_sentimiento_respuesta_tiene_los_campos_que_espera_java(self):
        import main as servicio

        campos_java = {"sentimiento", "confianza", "probabilidades", "modelo"}
        campos_python = set(servicio.SentimientoResponse.model_fields)

        self.assertEqual(campos_java, campos_python)

    def test_paises_validados_coinciden_con_los_entrenados(self):
        """
        OneHotEncoder con handle_unknown='ignore' convierte un pais no
        visto en un vector de ceros, sin error. Ese es el caso que
        PAISES_CONOCIDOS de main.py existe para rechazar.

        Comparar PAISES_CONOCIDOS contra las categorias reales del
        encoder es la unica forma de detectar que alguien anadio un pais
        al allowlist sin reentrenar, que reintroduciria el fallo silencioso.
        """
        import main as servicio

        categorias = set(self.modelo.named_steps["pre"]
                         .named_transformers_["pais"]
                         .categories_[0])

        self.assertEqual(
            servicio.PAISES_CONOCIDOS, categorias,
            "PAISES_CONOCIDOS en main.py ya no coincide con los paises que "
            "el modelo sabe interpretar. Reentrena o corrige la lista.")


class TestSentimiento(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        ruta = os.path.join(MODELOS_DIR, "sentimiento.joblib")
        if not os.path.exists(ruta):
            raise unittest.SkipTest(
                "Ejecuta primero: python entrenamiento.py")
        cls.modelo = joblib.load(ruta)

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
        import motor_sentimiento as ms

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