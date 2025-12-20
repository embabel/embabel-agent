# Embabel Agent Skills

Implementation of the [Agent Skills](https://agentskills.io/home) specification.

## Usage

```kotlin
val skills = Skills(
    name = "my-skills",
    description = "Skills for my agent"
)
    .withLocalSkills("/path/to/skills")
    .withGitHubSkills(owner = "myorg", repo = "my-skills-repo")
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

**Repository loading** (`withGitHubSkills`): Performs a shallow clone (depth=1) of the repository.

- If the target path contains `SKILL.md` at root, loads as a single skill
- Otherwise, scans immediate subdirectories for skills (same as local loading)
- Repository is automatically cleaned up after loading

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

**Single skill** (`loadSkillFromGitHub` on `GitHubSkillDefinitionLoader`): Loads one skill from a specific path in the repository.

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

## Limitations

- **Script execution**: Skills with `scripts/` directories log a warning. Script execution is not yet supported.
- **allowed-tools**: The field is parsed but not enforced.
