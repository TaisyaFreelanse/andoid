FROM minio/minio:latest

# Создаем директорию для данных
RUN mkdir -p /data

# MinIO server - порт 9000 для API, 9001 для консоли
CMD ["server", "/data", "--console-address", ":9001"]

EXPOSE 9000 9001

