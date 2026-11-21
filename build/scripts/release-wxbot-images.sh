#!/bin/sh
# Build and push the wxbot variants and lock every runtime image to an amd64 digest.
set -eu
if [ "$#" -ne 7 ]; then
    echo 'Usage: release-wxbot-images.sh CLIENT_REPO CLIENT_COMMIT ADMIN_REPO ADMIN_COMMIT REGISTRY/NAMESPACE UPSTREAM_IMAGES_JSON OUTPUT_LOCK' >&2
    exit 2
fi
client_repo=$1
client_commit=$2
admin_repo=$3
admin_commit=$4
target=$5
sources=$6
output=$7
workspace=$(mktemp -d)
trap 'rm -rf -- "$workspace"' EXIT HUP INT TERM
import_commit() {
    repo=$1
    commit=$2
    destination=$3
    resolved=$(git -C "$repo" rev-parse --verify "$commit^{commit}")
    if [ "$commit" != "$resolved" ]; then
        echo 'Use the full, immutable commit SHA' >&2
        exit 2
    fi
    mkdir -p "$destination"
    git -C "$repo" archive --format=tar "$resolved" > "$workspace/source.tar"
    tar -xf "$workspace/source.tar" -C "$destination"
}
import_commit "$client_repo" "$client_commit" "$workspace/client"
import_commit "$admin_repo" "$admin_commit" "$workspace/admin"
client_ref="$target/wechat-robot-client:$client_commit"
admin_ref="$target/wechat-robot-admin-backend:$admin_commit"
build_image() {
    variant=$1
    ref=$2
    revision=$3
    set --
    if [ -n "${WX_DOCKERFILE_FRONTEND:-}" ]; then
        set -- --build-arg "BUILDKIT_SYNTAX=$WX_DOCKERFILE_FRONTEND"
    fi
    if [ -n "${WX_GO_BUILD_IMAGE:-}" ]; then
        set -- "$@" --build-context "golang:1.25.8=docker-image://$WX_GO_BUILD_IMAGE"
    fi
    if [ "$variant" = admin ] && [ -n "${WX_ALPINE_BUILD_IMAGE:-}" ]; then
        set -- "$@" --build-context "alpine:latest=docker-image://$WX_ALPINE_BUILD_IMAGE"
    fi
    docker buildx build --platform linux/amd64 --push --tag "$ref" \
        --label "org.opencontainers.image.revision=$revision" --build-arg "VERSION=$revision" \
        --metadata-file "$workspace/$variant.json" "$@" "$workspace/$variant"
}
build_image client "$client_ref" "$client_commit"
build_image admin "$admin_ref" "$admin_commit"
python3 - "$workspace" "$sources" "$output" "$client_ref" "$admin_ref" <<'PY'
import json
import pathlib
import re
import subprocess
import sys

stage, sources, output, client, admin = sys.argv[1:]
stage = pathlib.Path(stage)
images = json.loads(pathlib.Path(sources).read_text(encoding='utf-8'))
expected = {'redis', 'mysql', 'qdrant', 'wechat-auth', 'slider', 'uuid', 'word-cloud', 'jimeng', 'mcp', 'admin-web', 'nginx', 'protocol', 'backup'}
if set(images) != expected:
    raise SystemExit(f'Expected image keys: {sorted(expected)}')
for key, tag, meta in [('client', client, 'client.json'), ('admin-api', admin, 'admin.json')]:
    digest = json.loads((stage / meta).read_text())['containerimage.digest']
    images[key] = tag.rsplit(':', 1)[0] + '@' + digest
ref_pattern = re.compile(r'[a-z0-9][a-z0-9.-]+(?::[0-9]+)?/[a-z0-9][a-z0-9_./-]*@sha256:[a-f0-9]{64}')
for key, ref in images.items():
    if not isinstance(ref, str) or not ref_pattern.fullmatch(ref) or '..' in ref:
        raise SystemExit(f'{key} must use an explicit registry, path and sha256 digest')
    subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', ref], check=True)
    platform = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Os}}/{{.Architecture}}', ref], text=True).strip()
    if platform != 'linux/amd64':
        raise SystemExit(f'{key} resolved to {platform}')
p = pathlib.Path(output)
tmp = p.with_suffix('.tmp')
tmp.write_text(json.dumps(images, indent=2, sort_keys=True) + '\n', encoding='utf-8')
tmp.replace(p)
PY
