#!/bin/bash
MODULES=$@
DOCKER_ORG=${DOCKER_ORG:-$USER}
DOCKER_REGISTRY=${DOCKER_REGISTRY:-quay.io}

export DOCKER_ORG
export DOCKER_REGISTRY

echo "Using docker registry ${DOCKER_REGISTRY}"
echo "Using docker org ${DOCKER_ORG}"

if [ "$MODULES" != "" ]; then
    echo "Restricting build to ${MODULES}"
    for module in $MODULES
    do
        pushd $module
        make && make docker_build && make docker_tag && make docker_push
        popd
    done
else
    make && make docker_build && make docker_tag && make docker_push
fi
