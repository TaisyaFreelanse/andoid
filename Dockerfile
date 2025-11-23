FROM minio/minio:latest

# Создаем директорию для данных
RUN mkdir -p /data

# MinIO server - используем только порт 9000 для API
# Console отключена, так как на Render сложно настроить несколько портов
# Для управления используйте MinIO Client (mc) или API
CMD ["server", "/data"]

EXPOSE 9000

