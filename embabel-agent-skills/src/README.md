# Embabel Agent Skills

Implementation of the [Agent Skills](https://agentskills.io/home) specification.

## Usage

```kotlin
val skills = Skills(
    name = "my-skills",
    description = "Skills for my agent"
)
    .withLocalSkills("/path/to/skills")
    .withGitHubUrl("https://github.com/anthropics/skills/tree/main/skills")
```

## Loading Behavior

### Local Directory Loading

**Single skill** (`withLocalSkill`): Loads a skill from a directory containing `SKILL.md`.

```
my-skill/
├── SKILL.md        # Required
├── scripts/        # Optional
├── references/     # Optional
└── assets/         # Optional
```

**Multiple skills** (`withLocalSkills`): Scans immediate subdirectories (depth 1) for `SKILL.md` files. Does not recurse into nested directories.

```
skills/
├── skill-a/
│   └── SKILL.md    # ✓ Loaded
├── skill-b/
│   └── SKILL.md    # ✓ Loaded
├── nested/
│   └── skill-c/
│       └── SKILL.md  # ✗ NOT loaded (too deep)
└── README.md       # Ignored (not a directory)
```

### GitHub Loading

**URL-based loading** (`withGitHubUrl`): Load skills directly from a GitHub URL. The URL is parsed to extract owner, repo, branch, and path.

```kotlin
// Load from a GitHub URL (owner, repo, branch, path inferred)
skills.withGitHubUrl("https://github.com/anthropics/skills/tree/main/skills")

// Single skill from URL
skills.withGitHubUrl("https://github.com/owner/repo/tree/main/path/to/my-skill")
```

Supported URL formats:
- `https://github.com/owner/repo`
- `https://github.com/owner/repo/tree/branch`
- `https://github.com/owner/repo/tree/branch/path/to/skills`

**Explicit parameters** (`withGitHubSkills`): Performs a shallow clone (depth=1) of the repository.

```kotlin
// Load all skills from repo root
skills.withGitHubSkills(owner = "myorg", repo = "skills")

// Load skills from a subdirectory
skills.withGitHubSkills(
    owner = "myorg",
    repo = "monorepo",
    skillsPath = "agent-skills"
)

// Load from a specific branch
skills.withGitHubSkills(
    owner = "myorg",
    repo = "skills",
    branch = "v2"
)
```

**Behavior:**
- If the target path contains `SKILL.md` at root, loads as a single skill
- Otherwise, scans immediate subdirectories for skills (same as local loading)
- Repository is automatically cleaned up after loading

### Git URL Loading

For non-GitHub repositories, use `GitHubSkillDefinitionLoader.fromGitUrl()`:

```kotlin
val loader = GitHubSkillDefinitionLoader.create()
val skills = loader.fromGitUrl(
    url = "https://gitlab.com/myorg/skills.git",
    branch = "main",
    skillsPath = "skills"
)
```

## Validation

Skills are validated on load:

- **Frontmatter validation**: name, description, and optional fields checked per spec
- **File reference validation**: paths referenced in instructions (e.g., `scripts/build.sh`) must exist
- **Name/directory match**: skill name must match its parent directory name

Validation can be disabled:

```kotlin
val loader = DefaultDirectorySkillDefinitionLoader(validateFileReferences = false)
```

## Script Execution

Skills can include executable scripts in their `scripts/` directory. To enable script execution, configure a `SkillScriptExecutionEngine`:

### ProcessSkillScriptExecutionEngine (Direct)

Runs scripts directly on the host machine:

```kotlin
import com.embabel.agent.skills.script.ProcessSkillScriptExecutionEngine
import com.embabel.agent.skills.script.ScriptLanguage
import kotlin.time.Duration.Companion.seconds

val engine = ProcessSkillScriptExecutionEngine(
    timeout = 30.seconds,
    supportedLanguages = setOf(ScriptLanguage.PYTHON, ScriptLanguage.BASH),
)

val skills = Skills("my-skills", "Skills with scripts")
    .withGitHubUrl("https://github.com/anthropics/skills/tree/main/skills/pdf")
    .withScriptExecutionEngine(engine)
```

### DockerSkillScriptExecutionEngine (Sandboxed)

Runs scripts in an isolated Docker container for security. This delegates to `DockerExecutor` from the `embabel-agent-sandbox` module:

```kotlin
import com.embabel.agent.skills.script.DockerSkillScriptExecutionEngine

val engine = DockerSkillScriptExecutionEngine(
    image = "embabel/agent-sandbox:latest",
    timeout = 60.seconds,
    supportedLanguages = setOf(ScriptLanguage.PYTHON, ScriptLanguage.BASH),
    memoryLimit = "512m",
    cpuLimit = "1.0",
)

val skills = Skills("my-skills", "Skills with sandboxed scripts")
    .withGitHubUrl("https://github.com/anthropics/skills/tree/main/skills/pdf")
    .withScriptExecutionEngine(engine)
```

**Building the sandbox image:**

```bash
docker build -t embabel/agent-sandbox:latest ./embabel-agent-skills/docker
```

The sandbox image includes:
- Python 3 with common packages (pypdf, pdf2image, pillow, requests, pyyaml, jinja2)
- Node.js and npm
- Common CLI tools (git, jq, curl, ripgrep, etc.)

**Maximum isolation:**

```kotlin
val engine = DockerSkillScriptExecutionEngine.isolated()  // No network, limited CPU/memory
```

### PodmanSkillScriptExecutionEngine (Daemonless Sandboxed)

Runs scripts in an isolated Podman container. Podman is a daemonless, rootless container engine that is CLI-compatible with Docker — it does not require a running daemon or root privileges, making it suitable for environments where Docker Desktop is unavailable or undesired.

```kotlin
import com.embabel.agent.skills.script.PodmanSkillScriptExecutionEngine

val engine = PodmanSkillScriptExecutionEngine(
    image = "embabel/agent-sandbox:latest",
    timeout = 60.seconds,
    supportedLanguages = setOf(ScriptLanguage.PYTHON, ScriptLanguage.BASH),
    memoryLimit = "512m",
    cpuLimit = "1.0",
)

val skills = Skills("my-skills", "Skills with sandboxed scripts")
    .withGitHubUrl("https://github.com/anthropics/skills/tree/main/skills/pdf")
    .withScriptExecutionEngine(engine)
```

**Building the sandbox image with Podman** (Podman can build Dockerfiles directly):

```bash
podman build -t embabel/agent-sandbox:latest ./embabel-agent-skills/docker
```

The same OCI image built with Docker can also be used with Podman, and vice versa.

**Maximum isolation:**

```kotlin
val engine = PodmanSkillScriptExecutionEngine.isolated()  // No network, limited CPU/memory
```

### Script Environment

Scripts receive these environment variables:

| Variable | Description |
|----------|-------------|
| `INPUT_DIR` | Directory containing input files (read-only in Docker) |
| `OUTPUT_DIR` | Directory for output artifacts |

Output files written to `OUTPUT_DIR` are collected as artifacts after execution.

### Input File Security

Both execution engines use `FileTools` for secure input file path resolution:

- Paths are resolved relative to a configurable root directory (defaults to current working directory)
- Path traversal attempts (e.g., `../../../etc/passwd`) are blocked
- Only files within the root directory can be accessed

To customize the root directory:

```kotlin
import com.embabel.agent.tools.file.FileTools

val engine = DockerSkillScriptExecutionEngine(
    fileTools = FileTools.readWrite("/path/to/allowed/directory"),
)
```

## Limitations

- **allowed-tools**: The field is parsed but not enforced.
