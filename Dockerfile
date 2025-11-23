FROM minio/minio:latest

# Создаем директорию для данных
RUN mkdir -p /data

# MinIO server - порт 9000 для API, 9001 для консоли
# MINIO_BROWSER_REDIRECT_URL должен быть установлен через переменные окружения
CMD ["server", "/data", "--console-address", ":9001"]

EXPOSE 9000 9001

