# Embabel Agent Sandbox

This optional module provides persistent scratch sessions, Docker-backed session
management, and explicit file publication. Add it alongside the Embabel Agent API
or a platform starter; the Embabel Agent BOM manages its version:

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-sandbox</artifactId>
</dependency>
```

It is ported from `embabel-agent-experimental`. Applications migrating to this
module should use the platform-aligned version and avoid including an older
experimental sandbox JAR on the same classpath. Package names remain
`com.embabel.agent.sandbox`.

## Execution choices

Use `ScratchTool` for model-written commands or code that needs a persistent
workspace. `DockerExecutor` instead creates a fresh container per execution, and
`ProcessExecutor` runs on the host. The separate
[skill script engines](../embabel-agent-skills/README.md#script-execution) execute
scripts already packaged with a skill; their Docker and Podman implementations
do not use the scratch session. Skill instructions and scratch tools can be
supplied together to a prompt runner.

## Container image

The persistent Docker backend requires a running Docker daemon and a locally
available image with the requested shell and command dependencies. Build the
included image from the repository root:

```sh
docker build -t embabel/agent-scratch:local ./embabel-agent-sandbox/src/main/docker
```

This image uses `/workspace` and includes Python, Node.js, Java, Graphviz, and
common utilities. It does not include every package a skill may need; prepare a
custom image for additional dependencies. The skill engines have their own image
recipe in `embabel-agent-skills/docker`. Both APIs historically default to the
tag `embabel/agent-sandbox:latest`, but the recipes differ. The examples here use
an explicit tag to avoid replacing one image with the other.

## Explicit file publication

`ScratchTool` executes commands in a persistent `SandboxSession`. Register
`ScratchPublishTool` alongside it to let the model publish finished files by their
absolute container paths. Execution alone does not export files, scan directories,
or configure `OUTPUT_DIR`.

```kotlin
import com.embabel.agent.api.tool.ArtifactSink
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.sandbox.ExecutionArtifact
import com.embabel.agent.sandbox.SandboxConfig
import com.embabel.agent.sandbox.ScratchPublishTool
import com.embabel.agent.sandbox.ScratchTool
import com.embabel.agent.sandbox.docker.ArtifactExportConfig
import com.embabel.agent.sandbox.docker.DockerSandboxSessionManager
import java.nio.file.Path

val manager = DockerSandboxSessionManager(
    artifactExport = ArtifactExportConfig(Path.of("sandbox-exports").toAbsolutePath()),
)
val scratch = ScratchTool(
    sessionManager = manager,
    config = SandboxConfig(
        enabled = true,
        image = "embabel/agent-scratch:local",
        network = false,
        propagateEnv = emptyList(),
    ),
)
val publish = Tool.sinkArtifacts(
    tool = ScratchPublishTool(scratch),
    clazz = ExecutionArtifact.File::class.java,
    sink = ArtifactSink { artifact ->
        val file = artifact as ExecutionArtifact.File
        // Persist or serve file.path using application-owned storage.
    },
)
// Register both scratch and publish with the application's PromptRunner:
// val runnerWithScratch = promptRunner.withTools(scratch, publish)
// Close scratch and manager when their owning application scope ends.
```

The model creates and verifies files with `scratch_run`, then calls
`scratch_publish` with an explicit list:

```json
{"paths":["/workspace/report.pdf","/workspace/data.csv"]}
```

For one file use `{"paths":["/workspace/report.pdf"]}`. The tool
and session expose only the list-based operation. Empty lists, duplicates, invalid
paths, and batches exceeding the file-count limit are rejected before transfer.
Only selected files are returned as `Tool.Result.WithArtifact`; neighboring
scripts and intermediate files remain in the container.
The session result pairs each successful artifact with its requested container
path, so files with the same basename from different directories remain distinct.
The tool still passes the plain `ExecutionArtifact.File` list to artifact sinks.

A batch uses one tool call and one session lock, with sequential per-file Docker
transfers under a shared deadline. This reduces model/tool round trips; it does
not combine the files into one Docker transfer. Successful files are returned
even if another file fails, with a diagnostic for each failed path. If none
succeed, the tool returns an error. Retry only failed paths to avoid publishing
successful files twice. The caller selects deliverables; the tool does not
classify file contents.

## Contracts and ownership

`ArtifactPublishingSession` is an optional capability implemented by
`DockerSandboxSession`. The existing `SandboxSession` and `ExecutionResult`
contracts are unchanged. A publication failure is reported by the publish tool
and does not change the result of the earlier command. Other backends can implement
the capability; unsupported sessions return a clear tool error.

Each publication creates an independent host copy, even for a previously
published path with identical content. A retry is explicit; there is no automatic
retry or content-hash tracking. Exported files remain readable after the container
closes. The application owns retention, storage quotas across calls, and download
access. `ArtifactSinkingTool` returns the original descriptor after invoking its
sink; a sink that moves the file must account for that original path being
consumed if downstream code also accesses the descriptor.

`hostRoot` must be application-owned and inaccessible to untrusted local writers.
Publication directories have owner-only permissions on POSIX filesystems; on
other platforms configure the root's access controls. Failed publications remove
their partial host files on a best-effort basis. The sandbox never deletes a
successfully published host file.

## Transfer, limits, and lifecycle

Publication verifies a normalized absolute path and rejects symbolic-link
ancestors, missing files, and non-regular files. It transfers the selected file
using paths without backslashes, colons, double quotes, or control characters;
double quotes are excluded because of Windows Docker CLI argument handling. It copies
directly with `docker cp` without `-L`. No temporary container copy or hashing is
required. The bounded tar stream is staged on the host and validated before the
file becomes available. Only the selected filename and one regular file are
accepted. Links, directories, sparse files, extra entries, invalid paths,
malformed archives, and oversized metadata fail publication.

Defaults are 20 selected files and 50 MiB of successfully published file payload
per call, with 10 MiB per file, 12 MiB per transferred archive, 32 physical archive
headers per file, and one 30-second deadline for the entire call. Failed attempts
are also bounded by the per-archive limit, batch file count, and shared deadline.
Empty files consume a file slot but no payload allowance.
Extended headers are limited to 16 KiB each
and eight consecutive records. The deadline is shared by waiting for the session
lock, source validation, all transfers, and archive processing. Interruptions retain
the calling thread's interrupt flag. Process startup and filesystem operations
are not forcibly interruptible, so this is not a hard real-time guarantee.
Host disk usage while staging can approach the archive plus file limits.

Execution, publication, copying, and lifecycle operations share the session lock;
idle eviction skips busy sessions. Publication refreshes session activity.
Command input is written concurrently with draining stdout/stderr, and its timeout
covers lock waiting, process I/O, and callback waiting. A timeout kills the local
Docker client; it does not guarantee termination of the command inside the
container. Finish all writes before publishing: background processes are not
frozen, and path checks plus transfer do not form a filesystem snapshot. Publication
is not a new access boundary within a container already accessible to model code.

## Application scope and capacity

Use a scratch tool/session per conversation or job when workspaces must be isolated.
A shared singleton tool shares its workspace and serializes calls across its users.
The publication limits bound one batch, not total storage or concurrent sessions.
Command stdout and stderr are each retained up to 1,048,576 characters, then
marked as truncated while the pipes continue draining. `captureOutput=false`
returns empty streams while still delivering stdout callbacks. Callback delivery
uses a 64-line queue; if a callback cannot keep up or one line exceeds 65,536
characters, execution fails or times out rather than retaining unbounded lines.

Saving artifacts is a side effect that happens before the final model response.
If an application retries the whole interaction, files can be published again.
Applications that require one stored copy per deliverable need request-scoped
identities and idempotent storage; random destination names preserve every retry.

## Verification

With Java 21, a running Docker daemon, and `alpine:latest` available locally:

```sh
mvn -pl embabel-agent-sandbox test
```

`DockerArtifactExporterTest`, `ArtifactTransferTest`, `ScratchPublishToolTest`, and
`ScratchToolArtifactTest` run without Docker. `DockerArtifactPublishTest` and
`DockerSessionIoTest` cover real selected-file transfer, lifecycle coordination,
and large-input/output regressions; they skip when Docker or the test image is
unavailable. Other existing tests may require the default sandbox image and its
optional tools, including Graphviz.
