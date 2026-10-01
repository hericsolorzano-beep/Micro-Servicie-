"""
Tests del motor de sentimiento y su degradacion.

El escenario que mas importa aqui no es "el transformer clasifica bien",
sino "cuando el transformer falla, el servicio sigue answering". Un
servicio de inferencia que devuelve 503 porque no pudo descargar un
modelo de 1 GB es peor que uno que responde con precision menor.

Ejecutar:  ../.venv/bin/python test_motor.py
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import motor_sentimiento as ms


class MotorTFIDFTest(unittest.TestCase):

    def test_carga_y_clasifica(self):
        motor = ms.MotorTFIDF()
        if not motor.disponible():
            self.skipTest("Ejecuta primero: python entrenamiento.py")

        r = motor.predecir("producto de muy mala calidad")
        self.assertEqual(r["sentimiento"], "negativo")
        self.assertEqual(r["modelo"], "tfidf_logreg_sentimiento")

    def test_probabilidades_suman_uno(self):
        motor = ms.MotorTFIDF()
        if not motor.disponible():
            self.skipTest("modelo no disponible")
        r = motor.predecir("excelente producto muy recomendado")
        self.assertAlmostEqual(sum(r["probabilidades"].values()), 1.0, places=4)


class MotorTransformerTest(unittest.TestCase):
    """Solo se ejecutan si el transformer esta cargado en el entorno."""

    @classmethod
    def setUpClass(cls):
        cls.motor = ms.MotorTransformer()
        if not cls.motor.cargar():
            raise unittest.SkipTest(
                "Transformer no disponible (sin red o sin RAM)")

    def test_etiquetas_traducidas_al_contrato(self):
        # Si no se tradujera, devolveria "positive" y el contrato de Java
        # (que espera positivo/negativo/neutro) se romperia en silencio.
        r = self.motor.predecir("excelente producto muy recomendado")
        self.assertIn(r["sentimiento"], {"positivo", "negativo", "neutro"})

    def test_texto_largo_se_recorta_en_vez_de_fallar(self):
        # El modelo acepta 512 tokens. Sin truncation, una reseña larga
        # lanzaria excepcion en vez de recortarse.
        largo = "producto excelente " * 900
        r = self.motor.predecir(largo)
        self.assertIn(r["sentimiento"], {"positivo", "negativo", "neutro"})

    def test_devuelve_las_tres_probabilidades(self):
        # Nuestro contrato las exige, aunque el pipeline por defecto solo
        # devuelva la clase ganadora.
        r = self.motor.predecir("nadie me contestó, un desastre")
        self.assertEqual(set(r["probabilidades"]), {"positivo", "negativo", "neutro"})

    def test_caso_de_sarcasmo(self):
        # El fallo clasico del clasificador de bolsa de palabras: "horrible"
        # es negativo, pero la frase es positiva.
        r = self.motor.predecir("totalmente horrible pero me encantó el acabado")
        self.assertEqual(r["sentimiento"], "positivo")


class DegradacionTest(unittest.TestCase):
    """Lo importante: qué pasa cuando el motor principal falla."""

    def _motor_con_transformer_roto(self):
        respaldo = ms.MotorTFIDF()
        if not respaldo.disponible():
            self.skipTest("modelo de respaldo no disponible")

        class TransformerQueExplota(ms.MotorTransformer):
            def disponible(self):
                return True

            def cargar(self):
                return True

            def predecir(self, texto):
                raise RuntimeError("CUDA out of memory")

        return ms.MotorConRespaldo(TransformerQueExplota(), respaldo)

    def test_cae_al_respaldo_si_el_transformer_falla(self):
        motor = self._motor_con_transformer_roto()

        r = motor.predecir("producto de muy mala calidad")

        # No debe propagar la excepcion: el endpoint tiene que responder.
        self.assertEqual(r["modelo"], "tfidf_logreg_sentimiento")
        self.assertEqual(r["sentimiento"], "negativo")
        self.assertTrue(motor.en_respaldo)

    def test_marca_el_respaldo_como_activo(self):
        motor = self._motor_con_transformer_roto()
        motor.predecir("cualquier texto")

        estado = motor.estado()
        self.assertTrue(estado["en_respaldo"])
        self.assertEqual(estado["motivo_respaldo"], "RuntimeError")

    def test_el_respaldo_sigue_respondiendo_tras_varios_fallos(self):
        # Un fallo por peticion no debe dejar el servicio a medias: la
        # segunda y tercera peticion tambien tienen que funcionar.
        motor = self._motor_con_transformer_roto()
        for _ in range(3):
            r = motor.predecir("pésima calidad")
            self.assertIn(r["sentimiento"], {"positivo", "negativo", "neutro"})

    def test_sin_motores_devuelve_error_explicito(self):
        vacio = ms.MotorTFIDF()
        vacio.modelo = None

        class TransformerAusente(ms.MotorTransformer):
            def disponible(self):
                return False

        motor = ms.MotorConRespaldo(TransformerAusente(), vacio)

        # Aqui si queremos excepcion: no hay con que responder, y el
        # endpoint la traduce a 503.
        with self.assertRaises(RuntimeError):
            motor.predecir("texto cualquiera")

    def test_transformer_sin_cargar_no_aborta_la_construccion(self):
        # El punto del diseno: que el transformer no se pueda cargar NO
        # impide que exista un motor utilizable.
        respaldo = ms.MotorTFIDF()
        if not respaldo.disponible():
            self.skipTest("modelo de respaldo no disponible")

        class TransformerInexistente(ms.MotorTransformer):
            def cargar(self):
                return False

        motor = ms.MotorConRespaldo(TransformerInexistente(), respaldo)
        r = motor.predecir("excelente producto")

        self.assertEqual(r["modelo"], "tfidf_logreg_sentimiento")
        self.assertIn("nota", r, "Deberia avisar de que va en modo respaldo")


if __name__ == "__main__":
    unittest.main(verbosity=2)