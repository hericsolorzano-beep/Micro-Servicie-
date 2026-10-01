FROM python:3.12-slim

WORKDIR /app

# Las dependencias primero: en cada rebuild se reutiliza la capa en cache
# mientras el requirements.txt no cambie. Al reves, cualquier cambio de
# codigo obliga a reinstalar torch (cientos de MB).
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    --extra-index-url https://download.pytorch.org/whl/cpu

COPY entrenamiento.py main.py motor_sentimiento.py ./

# Los modelos de scikit-learn se entrenan DENTRO de la imagen, asi que la
# imagen queda autocontenida: no depende de que alguien haya entrenado antes
# en su maquina, y todos los contenedores arrancan con la misma version.
RUN python entrenamiento.py

# Usuario sin privilegios, igual que la imagen de Java. Aqui importa mas:
# joblib.load deserializa pickle, que ejecuta codigo al cargar. Si el
# contenedor corre como root, cualquier via de escritura sobre los modelos
# se convierte en ejecucion de codigo como root.
RUN useradd --system --uid 1001 --user-group --create-home pythonuser

# El transformer (~1 GB) se descarga en el build, no en el arranque: en una
# imagen la descarga ocurre una vez, mientras que en tiempo de ejecucion cada
# reinicio del contenedor la pagaria de nuevo.
#
# HF_HOME se fija ANTES de descargar, y la descarga se hace ya como
# pythonuser. Si se descargara como root (el default antes del USER), los
# pesos quedarian en /root/.cache, ilegibles para el usuario que arranca el
# servicio, y el transformer se degradaria en silencio al clasificador
# pequeno. El orden de estas lineas no es decorativo.
ENV HF_HOME=/home/pythonuser/.cache/huggingface
RUN mkdir -p $HF_HOME && chown -R pythonuser:pythonuser /home/pythonuser

USER pythonuser:pythonuser

# Si la descarga falla (sin red), el build falla de forma ruidosa. Un build
# rojo es mejor que una imagen que arranca "medio" y degrada sin avisar.
RUN python -c "from transformers import pipeline; \
    pipeline('sentiment-analysis', model='cardiffnlp/twitter-xlm-roberta-base-sentiment')"

# Con los pesos ya dentro de la imagen, el servicio arranca sin red. Es lo
# que hace que un contenedor no dependa de Hugging Face para levantarse.
ENV HF_HUB_OFFLINE=1

EXPOSE 8000

# --workers 1 a proposito: los modelos ocupan memoria y forkeamos DESPUES
# de cargarlos, asi que N workers = N copias en RAM (con el transformer, cada
# una de ~1 GB). Para escalar, suben los contenedores, no los procesos.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]