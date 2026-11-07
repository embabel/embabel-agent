# embabel/agent-sandbox — batteries-included sandbox for AI agents
#
# Includes: Python 3, Node.js, Perl, Java, SQLite, curl, git, jq, and common utilities.
# Designed for agentic tool use: data processing, scripting, API calls, file manipulation.

FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# Core utilities
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    curl \
    wget \
    git \
    jq \
    unzip \
    zip \
    ca-certificates \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

# Python 3 with pip
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    && ln -sf /usr/bin/python3 /usr/bin/python \
    && rm -rf /var/lib/apt/lists/*

# Node.js (LTS)
RUN curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# Java (headless JRE)
RUN apt-get update && apt-get install -y --no-install-recommends \
    default-jre-headless \
    && rm -rf /var/lib/apt/lists/*

# Perl
RUN apt-get update && apt-get install -y --no-install-recommends \
    perl \
    && rm -rf /var/lib/apt/lists/*

# SQLite (in-memory or file-based database)
RUN apt-get update && apt-get install -y --no-install-recommends \
    sqlite3 \
    libsqlite3-dev \
    && rm -rf /var/lib/apt/lists/*

# Graphviz (dot files → SVG/PNG), ImageMagick, gnuplot
RUN apt-get update && apt-get install -y --no-install-recommends \
    graphviz \
    imagemagick \
    gnuplot-nox \
    plantuml \
    && rm -rf /var/lib/apt/lists/*

# Common Python packages for data work, visualization, and image generation
RUN pip3 install --no-cache-dir --break-system-packages \
    requests \
    pandas \
    numpy \
    matplotlib \
    pyyaml \
    beautifulsoup4 \
    lxml \
    Pillow \
    diagrams

# Working directory
WORKDIR /workspace

# Default command keeps the container alive for exec
CMD ["sleep", "infinity"]
