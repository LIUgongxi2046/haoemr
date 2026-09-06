#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd "$script_dir/.." && pwd)
cd "$project_dir"

required_files=(
  LICENSE
  NOTICE
  COMMERCIAL_LICENSE.md
  TRADEMARKS.md
  CONTRIBUTING.md
  CLA.md
  CODE_OF_CONDUCT.md
  SECURITY.md
  THIRD_PARTY_NOTICES.md
  .github/PULL_REQUEST_TEMPLATE.md
)

for file in "${required_files[@]}"; do
  if [[ ! -s "$file" ]]; then
    echo "missing_or_empty=$file" >&2
    exit 1
  fi
done

grep -Fq 'GNU AFFERO GENERAL PUBLIC LICENSE' LICENSE
grep -Fq '13. Remote Network Interaction; Use with the GNU General Public License.' LICENSE
grep -Fq 'AGPL-3.0-only' README.md
grep -Fq 'https://github.com/LIUgongxi2046/haoemr' README.md
grep -Fq 'https://github.com/LIUgongxi2046/haoemr' web/src/vue/views/LoginContextPage.vue
grep -Fq 'https://github.com/LIUgongxi2046/haoemr' web/src/vue/components/ClinicalShell.vue

node - <<'NODE'
const fs = require('node:fs');

for (const file of ['web/package.json', 'contracts/package.json']) {
  const value = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (value.license !== 'AGPL-3.0-only') {
    throw new Error(`${file} must declare AGPL-3.0-only`);
  }
}

const openapi = JSON.parse(fs.readFileSync('contracts/openapi.json', 'utf8'));
if (openapi.info?.license?.identifier !== 'AGPL-3.0-only') {
  throw new Error('contracts/openapi.json must declare AGPL-3.0-only');
}
NODE

printf '%s\n' '{"gate":"PASS","license":"AGPL-3.0-only","repository":"https://github.com/LIUgongxi2046/haoemr"}'
