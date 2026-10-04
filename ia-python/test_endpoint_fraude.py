"""
Tests del endpoint de fraude: consistencia entre probabilidad, umbral y
nivel de riesgo.

Este archivo existe por un bug real. El servicio guardaba el umbral
entrenado (0.30) y lo REPORTABA en la respuesta, pero decidia es_fraude
con predict(), que usa argmax y por tanto 0.5. El sistema hacia lo
contrario de lo que decian sus propias metricas.

El sintoma era discreto y por eso merece un test: una transaccion con
probabilidad 0.50 salia

    es_fraude=false, nivel_riesgo="alto"

y Java convertia eso en APROBADA para algo que el propio nivel de riesgo
daba por sospechoso. Ninguna excepcion, ningun 500: solo una decision
incoherente.

Ejecutar:  ../.venv/bin/python test_endpoint_fraude.py
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import main as servicio


class ConsistenciaUmbralTest(unittest.TestCase):
    """es_fraude debe ser coherente con el umbral que se reporta."""

    def setUp(self):
        """
        Filas REALES del dataset de OpenML, no inventadas.

        La primera version de este test escribia a mano unas filas
        "de fraude" copiadas de una salida truncada, y resulto que tenian
        probabilidad 0.05: no eran fraude en absoluto. El test pasaba por
        la razon equivocada.

        Estas dos salen de dataset_fraude y estan verificadas: la de
        fraude da 0.99 y la legitima 0.00.
        """
        self.fraude = ([-2.756, 0.6838, -1.3902, 1.5019, -1.1656, -0.1312, -1.4787, -0.2469, -0.1005, -2.3011, 1.9145, -3.831, 0.7195, -6.353, 1.4387, -3.2972, -4.863, -2.0024, 1.5452, -0.1069, 0.3205, 0.611, 0.1749, -0.5022, -0.1747, 1.1792, -1.1663, 0.8212], 101.5)
        self.legitima = ([2.3333, -0.5512, -2.7386, -0.9903, 0.3552, -1.555, 0.4152, -0.6059, -0.8094, 0.9784, -1.6055, -1.6082, -1.2683, 0.6537, -0.3456, 0.3353, 0.3565, -1.3627, 1.0383, -0.1178, 0.4185, 1.2258, -0.3137, -0.6468, 0.8186, 0.3482, -0.1063, -0.1019], 20.0)

    def _pedir(self, fila):
        componentes, monto = fila
        return servicio.predecir_fraude(servicio.TransaccionRequest(
            componentes=componentes, monto=monto, hora=12, pais="ES",
            distancia_km=100.0))

    def test_es_fraude_coincide_con_el_umbral_reportado(self):
        """La invariante central: probabilidad >= umbral <=> es_fraude."""
        for nombre, fila in (("fraude", self.fraude),
                             ("legitima", self.legitima)):
            r = self._pedir(fila)
            esperado = r.probabilidad >= r.umbral
            self.assertEqual(
                r.es_fraude, esperado,
                f"{nombre}: probabilidad={r.probabilidad} "
                f"umbral={r.umbral} pero es_fraude={r.es_fraude}. "
                "El umbral se esta reportando pero no se esta usando.")

    def test_no_hay_nivel_alto_con_es_fraude_falso(self):
        """
        El bug concreto, aislado.

        Con el umbral ignorado, una probabilidad de 0.50 daba
        es_fraude=false (predict usa 0.5) pero nivel_riesgo="alto" (el
        nivel si usaba el umbral). Java traducía esa incoherencia a
        APROBADA sobre algo que el propio servicio daba por sospechoso.
        """
        r = self._pedir(self.fraude)

        if not r.es_fraude:
            self.fail(
                f"es_fraude=false pero nivel={r.nivel_riesgo} "
                f"(prob={r.probabilidad}, umbral={r.umbral})")

    def test_el_umbral_es_el_entrenado_no_el_default(self):
        """
        Si el umbral fuera 0.5 (el default de predict), el servicio
        haria EXACTAMENTE lo que predict() y el umbral guardado seria
        decorativo.
        """
        self.assertNotEqual(
            servicio.umbral_fraude, 0.5,
            "El umbral deberia ser el elegido en el entrenamiento (0.30). "
            "Si vuelve a 0.5, se ha perdido la configuracion al cargar.")
        self.assertLess(servicio.umbral_fraude, 0.5)

    def test_fraude_real_detectado_y_legitima_real_aprobada(self):
        """Comprobacion de humo con filas reales del dataset."""
        r_fraude = self._pedir(self.fraude)
        r_legitima = self._pedir(self.legitima)

        self.assertTrue(r_fraude.es_fraude,
                        f"Fraude real no detectado (prob={r_fraude.probabilidad})")
        self.assertFalse(r_legitima.es_fraude,
                         f"Legitima real marcada como fraude "
                         f"(prob={r_legitima.probabilidad})")

    def test_nivel_coherente_con_cualquier_umbral(self):
        """
        La invariante debe aguantar si alguien cambia el umbral.

        Los niveles se calculaban con cortes FIJOS (0.85 critico, 0.50
        alto), lo cual solo era coherente mientras el umbral fuese <=
        0.50. Con umbral 0.70 y probabilidad 0.60, la respuesta decia

            es_fraude=false, nivel_riesgo="alto"

        a la vez. No es un caso teorico: subir el umbral es la primera
        palanca que se toca cuando el equipo quiere reducir falsos
        positivos, asi que es el cambio mas probable de todos.

        Por eso el test no fija un umbral, los recorre. Si alguien
        reintroduce cortes fijos, falla aqui y no en produccion.
        """
        original = servicio.umbral_fraude
        try:
            for umbral in (0.05, 0.30, 0.45, 0.55, 0.70, 0.90):
                servicio.umbral_fraude = umbral
                for nombre, fila in (("fraude", self.fraude),
                                     ("legitima", self.legitima)):
                    r = self._pedir(fila)
                    if r.es_fraude:
                        continue
                    # Asercion y no fail() incondicional: fail() rompia
                    # anunciando nivel='bajo', que es justo el valor
                    # correcto. Un test que falla cuando todo va bien
                    # entrena a ignorar su propio aviso.
                    self.assertEqual(
                        r.nivel_riesgo, "bajo",
                        f"umbral={umbral} {nombre}: es_fraude=false pero "
                        f"nivel_riesgo={r.nivel_riesgo!r}. Si no es fraude, "
                        "el nivel tiene que ser 'bajo'.")
        finally:
            servicio.umbral_fraude = original

    def test_nivel_crece_con_la_probabilidad(self):
        """El nivel no puede bajar al subir la probabilidad."""
        original = servicio.umbral_fraude
        servicio.umbral_fraude = 0.30
        try:
            orden = {"bajo": 0, "medio": 1, "alto": 2, "critico": 3}
            niveles = [orden[self._pedir(f).nivel_riesgo]
                       for f in (self.legitima, self.fraude)]
            self.assertLessEqual(niveles[0], niveles[1],
                                 "El fraude real no puede tener un riesgo "
                                 "menor que la transaccion legitima.")
        finally:
            servicio.umbral_fraude = original

    def test_umbral_mas_bajo_detecta_mas_fraude(self):
        """
        Motivo por el que el umbral se baja a 0.30: perder un fraude
        cuesta mas que revisar una transaccion limpia de mas.
        """
        r_bajo = self._pedir(self.fraude)

        # Con umbral 0.5 la misma fila puede salir no-fraude.
        con_medio = r_bajo.probabilidad >= 0.5

        self.assertGreaterEqual(
            r_bajo.es_fraude, con_medio,
            "Bajar el umbral nunca puede hacer que se detecte MENOS fraude")


if __name__ == "__main__":
    # Los tests necesitan los modelos cargados. Se arranca el lifespan
    # a mano en vez de esperar a que uvicorn lo haga, para que el archivo
    # se pueda ejecutar solo:  python test_endpoint_fraude.py
    import asyncio

    async def _arrancar():
        ctx = servicio.lifespan(servicio.app)
        await ctx.__aenter__()
        return ctx

    ctx = asyncio.run(_arrancar())
    try:
        unittest.main(verbosity=2)
    finally:
        pass