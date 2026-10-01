# Imagen de PostgreSQL para desarrollo.
#
# En produccion NO se usaria esto: bastaria la imagen oficial sin tocar.
# Aqui se anade una configuracion que evita un problema clasico al
# desarrollo en Docker: los volumenes montados en macOS y Windows estan
# sobre una capa del sistema de ficheros que no habla bien con el
# almacenamiento de Postgres (fsync y advisory locks), y el servidor
# muere al arrancar con "database system is not yet accepting
# connections".
#
# fsync=off y synchronous_commit=off hacen que los datos NO sobrevivan a
# una caida de energia. Es aceptable aqui porque la base es de pruebas y
# se puede reconstruir; en produccion seria inaceptable.

FROM postgres:17-alpine

# performance tweak solo para desarrollo
RUN echo "fsync = off" >> /usr/local/share/postgresql/postgresql.conf.sample \
    && echo "synchronous_commit = off" >> /usr/local/share/postgresql/postgresql.conf.sample \
    && echo "full_page_writes = off" >> /usr/local/share/postgresql/postgresql.conf.sample

# El limite por defecto de conexiones de Postgres es 100. Spring usa un
# pool de 10 por aplicacion, asi que 100 da margen de sobra para varios
# servicios sin agotar el servidor.
RUN echo "max_connections = 100" >> /usr/local/share/postgresql/postgresql.conf.sample
