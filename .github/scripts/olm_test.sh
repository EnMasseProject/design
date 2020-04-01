#!/bin/bash
set -e

TAG=${TAG:-latest}
export TEMPLATES=${PWD}/templates/build/enmasse-${TAG}

echo "Running OLM tests"
time make TESTCASE=olm.** PROFILE=olm-pr systemtests
