#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is not set}"

# Fetch alerts using GitHub CLI
gh api repos/"$GITHUB_REPOSITORY"/dependabot/alerts?state=open \
  --paginate \
  --jq '.' > alerts.json

# Group and format security alerts
cat alerts.json | jq -s '
  add
  | group_by([.dependency.package.name, .security_advisory.ghsa_id])
  | .[]
  | {
      key: "\(. | .[0].dependency.package.name) — \(. | .[0].security_advisory.ghsa_id)",
      paths: ([.[] | .dependency.manifest_path] | unique | sort),
      alerts: (
        [.[] | {
          severity: (.security_advisory.severity | ascii_downcase),
          summary: .security_advisory.summary,
          recommendation: (
            if (.security_advisory.recommendation != null and .security_advisory.recommendation != "") then
              .security_advisory.recommendation
            else
              "Upgrade \(.dependency.package.name) to a safe version (see advisory below)"
            end
          ),
          advisory_url: "https://github.com/advisories/\(.security_advisory.ghsa_id)"
        }]
        | unique
        | sort_by(
            .severity
            | if . == "critical" then 4
              elif . == "high" then 3
              elif . == "moderate" then 2
              elif . == "low" then 1
              else 0 end
          )
        | reverse
      )
    }
' | jq -r '
  . as $group |
  "\n🔐 \($group.key) (\($group.alerts | length) unique alerts)",
  "  Affected modules:",
  ($group.paths[] | "    - \(. )"),
  ($group.alerts[] |
    "  - Severity: \(.severity) | \(.summary)",
    "    ↪ Recommendation: \(.recommendation)",
    "    ↪ Advisory URL: \(.advisory_url)"
  )
'
