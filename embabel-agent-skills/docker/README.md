# Embabel Agent Skills Sandbox

Docker image for sandboxed script execution.

## Build

```bash
docker build -t embabel/agent-sandbox:latest .
```
or, with `Podman`
```bash
podman build -t embabel/agent-sandbox:latest .
```

Or from the repository root:

```bash
docker build -t embabel/agent-sandbox:latest ./embabel-agent-skills/docker
```
or, with `Podman`
```bash
podman build -t embabel/agent-sandbox:latest ./embabel-agent-skills/docker
```

## Usage

See the [main README](../README.md#dockerexecutionengine-sandboxed) for usage with `DockerExecutionEngine`.

## Customizing

To add packages, extend this image:

```dockerfile
FROM embabel/agent-sandbox:latest

USER root
RUN apt-get update && apt-get install -y your-package
USER agent

RUN uv pip install --system --break-system-packages your-python-package
```
