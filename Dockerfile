FROM minio/minio:latest

# Создаем директорию для данных
RUN mkdir -p /data

# MinIO server - используем один порт 9000 для API и Console
# На Render все порты проксируются через основной URL
# MINIO_BROWSER_REDIRECT_URL настроен через переменные окружения
CMD ["server", "/data", "--console-address", ":9000"]

EXPOSE 9000

