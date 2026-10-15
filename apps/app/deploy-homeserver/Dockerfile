# The Proxyma Home Server image.
#
# This is the SINGLE-USER edition - the same application the Windows installer and the Linux
# .deb run, and the same jar - packaged for a machine with no screen. It is not the server
# edition: there is no PostgreSQL and there are no accounts; an access code, printed in the log at
# every start, is the one way in. What it adds over the desktop packages is the ability to reach it
# from another machine, which is a decision its .env makes explicit rather than a default.
#
# You do not normally build this. Every release publishes the built image; this file is here for
# the case where you install from a package that carries the application jar beside it. The build
# context is this directory, and app.jar must be in it.

FROM eclipse-temurin:21-jre-noble

# tesseract-ocr  - the OCR engine. The Python wrapper below cannot work without it.
# tar            - packs the search index alongside the database file into one backup archive.
# subversion     - the svn command line, which the SVN connector runs for every listing and fetch.
# tini           - process 1. Java does not reap orphaned children (ChromaDB's workers, the
#                  converters, OCR); an init that does also forwards the stop signal.
RUN apt-get update && apt-get install -y --no-install-recommends \
        tar python3 python3-venv python3-pip \
        tesseract-ocr tesseract-ocr-eng tesseract-ocr-vie \
        subversion tini \
    && rm -rf /var/lib/apt/lists/*

# /app/python, and the path is load-bearing rather than a preference.
#
# This edition stores its vectors in ChromaDB, which the application STARTS ITSELF as a child
# process - the same thing it does on a desktop install. It finds the interpreter through
# PythonLayout.roots(dataRoot, installRoot), which looks in `<data>/runtime` and then in
# `<user.dir>/python`. WORKDIR below is /app, so this venv is the second candidate and is found
# with nothing to configure. Put it anywhere else and the application logs "assuming externally
# managed" and comes up with no vector store at all - search silently returns nothing.
#
# It carries the document toolchain too, so there is one interpreter in this image rather than
# two: chromadb for the vector store, markitdown for office and web formats, pdfplumber and
# pypdfium2 with pytesseract for PDFs that are scans rather than text.
#
# pip's cache is a BuildKit cache mount, shared with the server image: it never enters the image,
# and it survives between builds, so a rebuild does not download these wheels again.
RUN --mount=type=cache,id=proxyma-pip,target=/root/.cache/pip \
    python3 -m venv /app/python \
    && /app/python/bin/pip install --upgrade pip \
    && /app/python/bin/pip install \
        chromadb "markitdown[pdf,docx,pptx,xlsx,xls,outlook]" pdfplumber pypdfium2 pytesseract \
    && chmod -R a+rX /app/python

ENV PROXYMA_MARKITDOWN_EXE=/app/python/bin/markitdown \
    PROXYMA_PDF_EXTRACT_PYTHON=/app/python/bin/python3

# No browser is installed here, deliberately. Without this pin the Playwright driver checks its
# manifest on startup and downloads roughly 200 MB of browsers into every fresh container; the
# pure-Java renderer below needs none of it.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    PROXYMA_WEB_FETCH_JS_ENGINE=htmlunit

# The data root. AppPaths reads HOME on Linux and resolves $HOME/.local/share/proxyma, so pointing
# HOME at the mounted volume puts the database, the search index, the vector store, the
# conversations and the logs inside it - and nothing outside it.
ENV HOME=/data

# HOW DOCUMENTS GET IN: they are uploaded, and Proxyma owns the bytes.
#
# This is the one place the home server differs from the desktop packages it otherwise is. On a
# laptop a file source is a folder you already have, indexed where it sits and never written to.
# In a container that same feature means "a folder inside this container", which is a bind mount
# the operator has to declare, get the permissions right on, and restart the stack to add another
# of - before a single document can be indexed. So this edition uploads, exactly as the server
# edition does, and the sources you make are UPLOADED_FILES: deleting one deletes its bytes.
#
# SET ON THE IMAGE, NOT IN docker-compose.yml, deliberately. It is not a preference - it selects
# which of two connector types exists here, and the two have different deletion semantics. A user
# who turned it off in a compose file would get an instance whose only file source is a directory
# inside a container, which is the dead end this replaces.
#
# The storage root is /data, so uploads land in /data/uploads - inside the one mounted volume, so
# they are backed up by copying that directory like everything else. Nothing else in /data starts
# with "uploads" (the application's own files are under /data/.local/share/proxyma), so there is
# no collision to reason about.
ENV PROXYMA_FILE_UPLOAD_ENABLED=true \
    PROXYMA_STORAGE_LOCAL_ROOT=/data

# WHICH EDITION THIS IS. The jar is the desktop jar, so without this the About tab says "Proxyma
# Desktop" on a home server. /api/v1/config reports it as `homeServer` and the SPA names the
# edition from that. On the image for the same reason as the line above: it describes the package,
# not a preference an operator should be editing.
ENV PROXYMA_HOME_SERVER=true

# ALL INTERFACES, INSIDE THE CONTAINER. The jar listens on loopback by default (server.address in
# application-desktop.properties, since 2026-10-14), and loopback inside a container is unreachable
# from the port Docker publishes. Who reaches that port is BIND_ADDR's decision in .env (loopback
# by default), and whoever does still needs the access code (AccessGate) - which is what makes
# listening on every interface here safe.
ENV PROXYMA_SERVER_ADDRESS=0.0.0.0

# No window and no tray icon: there is no display. Without these every start tried Electron, a
# browser and the system tray, and logged a warning for each (editions-assembly-26).
ENV PROXYMA_DESKTOP_OPEN_BROWSER=false \
    PROXYMA_DESKTOP_SYSTEM_TRAY=false

# NOT ROOT. uid 10001 in group 0, with /data owned by that pair and writable by the group: that is
# the contract an orchestrator that picks its own uid relies on (OpenShift runs the image as an
# arbitrary uid that is always in group 0), and a named Docker volume is initialized from this
# directory's ownership, so it needs no chown either. install.sh's `user: HOST_UID:HOST_GID` still
# overrides it for the bind-mounted ./data. No VOLUME line: it would create an anonymous volume for
# every run that mounts nothing, and lose it at the next one.
RUN useradd --system --uid 10001 --gid 0 --home-dir /data --no-create-home \
        --shell /usr/sbin/nologin proxyma \
    && mkdir -p /data \
    && chown 10001:0 /data \
    && chmod 0770 /data

WORKDIR /app
COPY app.jar app.jar
# Proxyma Community is free software, AGPL-3.0-only: its license and copyright notice travel with it.
# tools/release/images.sh puts both in the build context beside app.jar.
COPY LICENSE NOTICE ./

# The open-source notices, out of the jar the build joined them into
# (apps/app/third-party/notices.xml), so /app/THIRD-PARTY-NOTICES.txt sits beside the application.
# A jar without them fails the image build here rather than shipping an image without them.
RUN python3 -c "import zipfile; open('/app/THIRD-PARTY-NOTICES.txt', 'wb').write(zipfile.ZipFile('/app/app.jar').read('BOOT-INF/classes/static/THIRD-PARTY-NOTICES.txt'))" \
    && chmod a+r /app/THIRD-PARTY-NOTICES.txt

EXPOSE 4246

# Ready, not merely alive: 200 once startup has finished and every database answers (ProbeController).
# The probe needs no access code (AccessGate leaves the health endpoints open) and arrives as
# 127.0.0.1, a Host the application always answers. curl is in the base image. The start period
# covers ChromaDB, which comes up before the port opens.
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD ["curl", "-fsS", "-o", "/dev/null", "--max-time", "4", "http://127.0.0.1:4246/api/v1/health/ready"]

# NO -Djava.net.preferIPv6Addresses HERE, and it is worth knowing why it was here.
# ChromaDB used to bind [::1] and be reached at "localhost", so the JVM was bent IPv6-first to
# find it. That flag is JVM-WIDE: on this image - a container on Docker's default IPv4-only
# bridge - it also made every OUTBOUND call prefer a AAAA record, and license.proxyma.ai is
# Cloudflare-fronted and dual-stack. Every Home Server install there has ever been failed to
# activate its license with "Network is unreachable": a paid edition that could not be licensed,
# because of a flag added to reach a loopback port. ChromaStoreService now binds and addresses
# chroma by literal 127.0.0.1, so nothing needs a resolver preference.
#
# The heap may grow to 40 percent of what the container is given rather than the JVM's default
# quarter: ChromaDB runs beside it in this container and keeps its index in memory, so the rest is
# left to it. -XX:+ExitOnOutOfMemoryError turns a JVM out of heap, which otherwise keeps running
# half wedged, into a restart the container's supervisor can see. tini passes the exit code
# through, so the application's own "restart me" (exit 3) reaches the restart policy as it is.
USER 10001
ENTRYPOINT ["/usr/bin/tini", "--", "java", "-XX:MaxRAMPercentage=40", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
