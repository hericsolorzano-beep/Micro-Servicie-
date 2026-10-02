"""
Prueba de carga contra el stack en ejecucion.

No es un benchmark de marketing: el objetivo es encontrar DONDE SE ROMPE y
documentarlo. Por eso se mide en fases y en cada una se anota el criterio
que falla, no solo el tiempo.

Lo que se mira:

  1. Latencia bajo concurrencia. Si el bulkhead (10 llamadas) no existiera,
     las peticiones se acumularian en el pool de hilos de Tomcat y la
     latencia creceria linealmente con la concurrencia. Con bulkhead, se
     estabiliza y las que no caben reciben 503 de inmediato.

  2. Que el bulkhead NO se degrade. 10 concurrentes contra un servicio que
     tarda ~350 ms es una prueba real: sin saturar, cada grupo de 10 tarda
     un ciclo; saturando, la latencia por peticion se multiplica.

  3. Que el circuit breaker se comporte. Se apaga Python a proposito y se
     mide cuando se abre, cuanto dura el corte y cuanto se recupera.

  4. Que PostgreSQL no se caiga ni se degrade con escrituras concurrentes.

Quebrar el servicio a proposito es parte del plan: un detector de fraude
que solo funciona mientras todo va bien no vale para nada.

Ejecutar:  ../.venv/bin/python prueba_carga.py
"""

import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

API = "http://127.0.0.1:8080"
V1 = f"{API}/api/v1"

# Componentes tomados de una fila REAL del dataset de OpenML. Usar una
# fila real importa: mandar 1.234 repetido 28 veces es valido, pero es
# una entrada que el modelo nunca vio y podria caer en una hoja extrema,
# falseando la latencia o el codigo de respuesta.
COMPONENTES = [
    -2.756, 0.6838, -1.3902, 1.5019, -1.1656, -0.1312, -1.4787, -0.2469,
    -0.1005, -2.3011, 1.9145, -3.831, 0.7195, -6.353, 1.4387, -3.2972,
    -4.863, -2.0024, 1.5452, -0.1069, 0.3205, 0.611, 0.1749, -0.5022,
    -0.1747, 1.1792, -1.1663, 0.8212,
]


def peticion(url, payload=None, token=None, timeout=30):
    """Una peticion. Devuelve (codigo, cuerpo, milisegundos)."""
    cabeceras = {"Content-Type": "application/json"}
    if token:
        cabeceras["Authorization"] = "Bearer " + token

    req = (urllib.request.Request(url, headers=cabeceras) if payload is None
           else urllib.request.Request(url, data=json.dumps(payload).encode(),
                                       headers=cabeceras))
    inicio = time.perf_counter()
    try:
        cuerpo = json.loads(urllib.request.urlopen(req, timeout=timeout).read())
        return 200, cuerpo, (time.perf_counter() - inicio) * 1000
    except urllib.error.HTTPError as e:
        try:
            cuerpo = json.loads(e.read())
        except Exception:
            cuerpo = {}
        return e.code, cuerpo, (time.perf_counter() - inicio) * 1000


def percentile(valores, p):
    if not valores:
        return 0.0
    ordenados = sorted(valores)
    i = min(int(len(ordenados) * p / 100), len(ordenados) - 1)
    return ordenados[i]


def resumen(nombre, resultados, esperado=200):
    """Imprime las metricas de una tanda y devuelve el desglose."""
    codigos = {}
    for codigo, _, _ in resultados:
        codigos[codigo] = codigos.get(codigo, 0) + 1

    tiempos = [ms for codigo, _, ms in resultados if codigo == esperado]

    print(f"\n  {nombre}")
    print(f"    respuestas: {codigos}")
    if tiempos:
        print(f"    latencia OK: p50 {statistics.median(tiempos):7.1f} ms   "
              f"p95 {percentile(tiempos, 95):7.1f} ms   "
              f"max {max(tiempos):7.1f} ms")
    fallos = len(resultados) - len(tiempos)
    if fallos:
        print(f"    fallidas:   {fallos}/{len(resultados)}")
    return codigos


def transaccion(monto=100.0, hora=12, pais="ES"):
    return {
        "componentes": COMPONENTES,
        "monto": monto,
        "hora": hora,
        "pais": pais,
        "distancia_km": 80,
    }


def obtener_token():
    import random
    import string
    email = "carga-" + "".join(random.choices(string.ascii_lowercase, k=8)) \
        + "@ejemplo.com"
    peticion(f"{V1}/sesiones/usuarios",
             {"email": email, "nombre": "Carga", "contrasena": "contrasena-larga-123"})
    codigo, cuerpo, _ = peticion(f"{V1}/sesiones/login",
                                 {"email": email, "contrasena": "contrasena-larga-123"})
    if codigo != 200:
        raise SystemExit(f"No se pudo obtener token: {codigo} {cuerpo}")
    return cuerpo["token"]


def tanda(nombre, cantidad, concurrentes, token):
    """Ejecuta `cantidad` peticiones con `concurrentes` en paralelo."""
    inicio = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrentes) as pool:
        resultados = list(pool.map(
            lambda _: peticion(f"{API}/api/transacciones", transaccion(), token),
            range(cantidad)))
    total = (time.perf_counter() - inicio) * 1000
    resumen(nombre, resultados)
    print(f"    pared:      {total/1000:.2f} s   "
          f"throughput: {cantidad / (total/1000):.1f} peticiones/s")
    return resultados


def main():
    print("=" * 70)
    print("PRUEBA DE CARGA")
    print("=" * 70)

    codigo, cuerpo, _ = peticion(f"{API}/actuator/health")
    print(f"\nsalud: [{codigo}] {cuerpo.get('status')}")
    if codigo != 200:
        raise SystemExit("El stack no esta sano; no tiene sentido medir carga.")

    token = obtener_token()
    print(f"token obtenido para las pruebas")

    # ---------------------------------------------------------------- 1
    print("\n" + "=" * 70)
    print("1. LATENCIA BAJO CONCURRENCIA")
    print("=" * 70)
    print("\nEl bulkhead permite 10 llamadas simultaneas a Python. Se"
          "\ncomprueba que la latencia NO crezca con la concurrencia: si\n"
          "creciera, seria porque las peticiones se acumulasen en el\n"
          "pool de hilos en vez de rechazarse.")

    for concurrentes in (1, 5, 10, 20, 40):
        # Suficientes peticiones para que el bulkhead llegue a saturarse
        # en los casos altos.
        cantidad = max(40, concurrentes * 4)
        tanda(f"{concurrentes} simultaneas x {cantidad} peticiones",
              cantidad, concurrentes, token)

    # ---------------------------------------------------------------- 2
    print("\n" + "=" * 70)
    print("2. SATURACION DEL BULKHEAD")
    print("=" * 70)
    print("\nCon 40 simultaneas y un bulkhead de 10, tiene que haber\n"
          "rechazos. Lo que se busca NO es un 500: es un 503 con el\n"
          "codigo SERVICIO_SATURADO, que dice al cliente que reintente\n"
          "en un momento, en vez de un fallo del sistema.")

    resultados = tanda("saturacion deliberada (40 simultaneas)", 200, 40, token)
    saturado = [r for r in resultados if r[0] == 503]
    if saturado:
        codigos = {r[1].get("codigo") for r in saturado}
        tiempos = [r[2] for r in saturado]
        print(f"    rechazos:   {len(saturado)}/{len(resultados)}")
        print(f"    codigos:    {codigos}")
        print(f"    latencia de los rechazos: p50 "
              f"{statistics.median(tiempos):.1f} ms  <- debe ser baja")
        if codigos != {"SERVICIO_SATURADO"}:
            print("    AVISO: hay rechazos con un codigo inesperado")
    else:
        print("    sin rechazos: el bulkhead no se activo")

    # ---------------------------------------------------------------- 3
    print("\n" + "=" * 70)
    print("3. ESCRITURAS CONCURRENTES EN POSTGRESQL")
    print("=" * 70)
    print("\nCada peticion guardada escribe una transaccion y 28\n"
          "componentes. Con 30 simultaneas son ~870 filas por segundo\n"
          "en la tabla de componentes: la prueba de si el pool de\n"
          "Hikari (10 conexiones) aguanta o si se produce espera.")

    # OJO: hace falta un token del PROPIO usuario, no el de otro. Con un
    # token ajeno, la comprobacion de propiedad responde 404 y la prueba
    # mide autenticacion en vez de escritura: 300 de 300 "fallidas" sin
    # que la base de datos participara. Ya me paso: la primera version de
    # este script hacia justo eso y el 404 parecio un fallo de PostgreSQL.
    usuario, token_esc = crear_usuario("esc")
    with ThreadPoolExecutor(max_workers=30) as pool:
        resultados = list(pool.map(
            lambda _: peticion(f"{V1}/usuarios/{usuario}/transacciones",
                               transaccion(), token_esc),
            range(300)))
    resumen("300 escrituras, 30 simultaneas", resultados)

    codigo, cuerpo, _ = peticion(
        f"{V1}/usuarios/{usuario}/transacciones?tamano=100", token=token_esc)
    print(f"    persistidas: {len(cuerpo) if codigo == 200 else 'ERROR'} "
          f"de 300 (tamano de pagina acotado a 100)")

    # ---------------------------------------------------------------- 4
    print("\n" + "=" * 70)
    print("4. CAIDA DEL SERVICIO DE IA (prueba destructiva)")
    print("=" * 70)
    print("\nSe para Python a proposito. Un detector de fraude que solo\n"
          "funciona mientras todo va bien no sirve para nada: hay que\n"
          "ver que se degrada sin mentir y se recupera solo.")

    parar = input(
        "\n  ¿Parir el contenedor ia-python ahora? (s/n) ")
    if parar.strip().lower() != "s":
        print("  Omitido.")
        return 0

    return medir_caida(token)


def crear_usuario(prefijo):
    """Devuelve (id, token) de un usuario recien creado."""
    import random
    import string
    email = f"{prefijo}-" + "".join(random.choices(string.ascii_lowercase, k=8)) \
        + "@ejemplo.com"
    peticion(f"{V1}/sesiones/usuarios",
             {"email": email, "nombre": "Carga", "contrasena": "contrasena-larga-123"})
    codigo, cuerpo, _ = peticion(f"{V1}/sesiones/login",
                                 {"email": email, "contrasena": "contrasena-larga-123"})
    return cuerpo["usuario_id"], cuerpo["token"]


def medir_caida(token):
    import subprocess

    def estado_ia():
        """
        Si el contenedor responde, por el puerto de la API, no por dentro.

        La primera version usaba "docker exec ia-python wget .../health" y
        daba 'caida' siempre, porque la imagen no tiene wget. Con esa
        sonda creia que Python estaba caido cuando estaba de pie, y las 25
        peticiones de la prueba devolvieron todas 200 sin comprobar nada.
        Un error en el instrumento hace que el experimento no mida nada,
        y es peor que no medir: da confianza falsa.
        """
        # No se usa http://127.0.0.1:8000 porque ia-python no publica
        # puertos: es un servicio interno de la red de compose, y a
        # proposito. Se comprueba por su estado en docker, que es ademas
        # lo que consulta el healthcheck de compose.
        try:
            salida = subprocess.run(
                ["sg", "docker", "-c",
                 "docker inspect -f {{.State.Running}} ia-python"],
                capture_output=True, text=True, timeout=10)
            return "ok" if salida.stdout.strip() == "true" else "caida"
        except Exception:
            return "caida"

    print(f"  estado de Python antes: {estado_ia()}")

    # AQUI SE PARA. La primera version de este script NO tenia esta linea:
    # solo comprobaba el estado y daba por hecho que Python se habia
    # parado. No se habia parado, asi que las 25 peticiones devolvieron
    # todas 200 y la prueba destructiva no midio nada en absoluto.
    # Parecer que se probo algo que no se probo es peor que no probarlo.
    print("  Parando el contenedor ia-python...")
    subprocess.run(["sg", "docker", "-c", "docker stop ia-python"],
                   capture_output=True)

    for i in range(20):
        if estado_ia() == "caida":
            print(f"  Python CAIDO (confirmado tras {i+1} s)")
            break
        time.sleep(1)
    else:
        print("  AVISO: no se pudo confirmar que Python este caido. "
              "Los resultados siguientes no significarian nada.")

    # Con Python caido, cada peticion falla. Se mide cuando se abre el
    # circuito: las siguientes deben ser casi instantaneas.
    print("\n  25 peticiones con Python parado (cronometrando cada una):")
    inicio = time.perf_counter()
    tiempos = []
    codigos = {}
    for i in range(25):
        codigo, cuerpo, ms = peticion(f"{API}/api/transacciones",
                                      transaccion(), token)
        tiempos.append(ms)
        codigos[codigo] = codigos.get(codigo, 0) + 1
        if i in (0, 1, 2, 4, 9, 14, 24):
            print(f"    peticion {i+1:2d}: [{codigo}] {ms:8.1f} ms  "
                  f"{cuerpo.get('codigo','')}")

    print(f"\n    codigos: {codigos}")
    print(f"    p50 antes de estabilizarse: "
          f"{statistics.median(tiempos[:5]):.1f} ms")
    print(f"    p50 al final:               "
          f"{statistics.median(tiempos[-5:]):.1f} ms")
    print(f"    total de las 25:            "
          f"{(time.perf_counter()-inicio):.2f} s")

    print("\n  health con Python caido:")
    codigo, cuerpo, _ = peticion(f"{API}/actuator/health")
    print(f"    [{codigo}] {cuerpo.get('status')}")

    print("\n  Arrancando Python de nuevo...")
    subprocess.run(["sg", "docker", "-c", "docker start ia-python"],
                   capture_output=True)
    print("  Esperando a que vuelva a estar sano (hasta 120 s)...")

    sano = False
    for i in range(120):
        time.sleep(1)
        if estado_ia() == "ok":
            sano = True
            print(f"  Python sano tras {i+1} s")
            break

    if not sano:
        print("  Python no volvio. Saliendo.")
        return 1

    print("\n  Peticiones de nuevo, tras la caida (el circuito deberia "
          "ir a half-open):")
    for i in range(6):
        codigo, cuerpo, ms = peticion(f"{API}/api/transacciones",
                                      transaccion(), token)
        print(f"    peticion {i+1}: [{codigo}] {ms:8.1f} ms  "
              f"{cuerpo.get('codigo') or cuerpo.get('accion')}")
        time.sleep(1)

    return 0


if __name__ == "__main__":
    sys.exit(main())
