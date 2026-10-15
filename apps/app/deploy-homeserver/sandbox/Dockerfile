# The Office Files sandbox: runs model-written Python for runPython, away from the app and its data.
#
# Built on the host by `docker compose` (the `office` profile), not pulled: it is small, and building
# it here keeps a third image out of the release. docker-compose.yml is what confines it - no route
# out, no volumes, read-only root, no capabilities, memory and process limits. This image only has to
# carry the libraries.
#
# Identical in apps/app/deploy/sandbox/ and apps/app/deploy-homeserver/sandbox/; runner.py is also
# the Desktop copy in proxyma-core-tools. tools/release/bundles.sh fails a release if they differ.
FROM python:3.13-slim

RUN pip install --no-cache-dir python-docx openpyxl python-pptx pandas matplotlib xlsxwriter pillow \
 && python -c "import matplotlib.pyplot"

WORKDIR /app
COPY runner.py sandbox_server.py ./

# nobody. The per-run directories live on the /work tmpfs the compose file mounts.
USER 65534:65534
ENV MPLCONFIGDIR=/tmp/mpl PYTHONDONTWRITEBYTECODE=1
EXPOSE 8080
CMD ["python", "-I", "/app/sandbox_server.py"]
