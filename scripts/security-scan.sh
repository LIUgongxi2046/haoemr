#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "$script_dir/.." && pwd)
scan_result=$(mktemp)
trap 'rm -f "$scan_result"' EXIT

private_key_pattern='-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
aws_key_pattern='AKIA[0-9A-Z]{16}'
openai_key_pattern='(^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{24,}'
github_key_pattern='(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{22,})'

cd "$project_dir"

# Prefer ripgrep when present, but never silently skip a check on hosts without it.
if command -v rg >/dev/null 2>&1; then
  # 1. Credential material anywhere in tracked source.
  if rg --hidden -l \
    --glob '!web/node_modules/**' --glob '!web/dist/**' --glob '!build/**' \
    --glob '!.git/**' --glob '!.playwright-cli/**' --glob '!evals/datasets/**' \
    "($private_key_pattern|$aws_key_pattern|$openai_key_pattern|$github_key_pattern)" . > "$scan_result"; then
    echo "Potential credential material detected:" >&2
    sed -n '1,20p' "$scan_result" >&2
    exit 1
  fi

  # 2. Production bundle identity leak.
  if [[ -d web/dist ]] && rg -q \
    '(dev-synthetic-token|018f0000-0000-7000-8000-00000000aa01|018f0000-0000-7000-8000-000000000001|合成患者甲|合成门诊患者|合成临床患者|合成协作医生)' \
    web/dist; then
    echo "Production bundle contains a development identity or synthetic patient identifier" >&2
    exit 1
  fi

  # 3. Inline secrets in production configuration.
  if rg -n \
    '^[[:space:]]+(password|client-secret|private-key|access-key|secret-key|api-key):[[:space:]]+[^$#[:space:]]' \
    src/main/resources/application-prod.yml > "$scan_result"; then
    echo "Production configuration contains an inline secret-like value:" >&2
    sed -n '1,20p' "$scan_result" >&2
    exit 1
  fi

  # 4. Production profile must stay synthetic-free.
  if rg -q '(dev-synthetic-token|spring\.profiles\.active:.*dev-synthetic)' \
    src/main/resources/application-prod.yml deploy/production.env.example; then
    echo "Production configuration enables a development identity or synthetic profile" >&2
    exit 1
  fi
else
  # Portable fallback using find + grep (macOS/BSD grep compatible).
  find . -type f \
    -not -path './web/node_modules/*' \
    -not -path './web/dist/*' \
    -not -path './build/*' \
    -not -path './.git/*' \
    -not -path './.playwright-cli/*' \
    -not -path './evals/datasets/*' \
    -print0 > "$scan_result"
  if xargs -0 grep -lE \
    "($private_key_pattern|$aws_key_pattern|$openai_key_pattern|$github_key_pattern)" < "$scan_result" > "$scan_result.hits" 2>/dev/null; then
    echo "Potential credential material detected:" >&2
    sed -n '1,20p' "$scan_result.hits" >&2
    rm -f "$scan_result.hits"
    exit 1
  fi
  rm -f "$scan_result.hits"

  if [[ -d web/dist ]] && grep -rqE \
    '(dev-synthetic-token|018f0000-0000-7000-8000-00000000aa01|018f0000-0000-7000-8000-000000000001|合成患者甲|合成门诊患者|合成临床患者|合成协作医生)' \
    web/dist; then
    echo "Production bundle contains a development identity or synthetic patient identifier" >&2
    exit 1
  fi

  if grep -nE \
    '^[[:space:]]+(password|client-secret|private-key|access-key|secret-key|api-key):[[:space:]]+[^$#[:space:]]' \
    src/main/resources/application-prod.yml > "$scan_result.hits" 2>/dev/null; then
    echo "Production configuration contains an inline secret-like value:" >&2
    sed -n '1,20p' "$scan_result.hits" >&2
    rm -f "$scan_result.hits"
    exit 1
  fi
  rm -f "$scan_result.hits"

  if grep -rqE '(dev-synthetic-token|spring\.profiles\.active:.*dev-synthetic)' \
    src/main/resources/application-prod.yml deploy/production.env.example; then
    echo "Production configuration enables a development identity or synthetic profile" >&2
    exit 1
  fi
fi

echo '{"credential_patterns":"PASS","production_bundle_development_identity":"PASS","production_config_inline_secrets":"PASS","production_profile_isolation":"PASS"}'
