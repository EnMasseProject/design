#!/usr/bin/env bash

#required environment variables
#ARTIFACTS_DIR

#optional envirinmnt dir
#SYSTEMTESTS_UPGRADED
#ENABLE_RBAC

CURDIR=`readlink -f \`dirname $0\``
source ${CURDIR}/test_func.sh
source "${CURDIR}/../../scripts/logger.sh"

TEST_PROFILE=${1}
TESTCASE=${2:-"io.enmasse.**"}

info "Running tests with profile: ${TEST_PROFILE}, tests: ${TESTCASE}"

failure=0

#environment info before tests
LOG_DIR="${ARTIFACTS_DIR}/openshift-info/"
mkdir -p ${LOG_DIR}
get_kubernetes_info ${LOG_DIR} services default "-before"
get_kubernetes_info ${LOG_DIR} pods default "-before"

#start system resources logging
${CURDIR}/system-stats.sh > ${ARTIFACTS_DIR}/system-resources.log &
STATS_PID=$!
info "process for checking system resources is running with PID: ${STATS_PID}"

export KUBERNETES_API_TOKEN=$(oc whoami -t)
export KUBERNETES_NAMESPACE=${OPENSHIFT_PROJECT}
export_required_env

#start docker logging
DOCKER_LOG_DIR="${ARTIFACTS_DIR}/docker-logs"
${CURDIR}/docker-logs.sh ${DOCKER_LOG_DIR} > /dev/null 2> /dev/null &
LOGS_PID=$!
info "process for syncing docker logs is running with PID: ${LOGS_PID}"

#run tests
if [[ "${TEST_PROFILE}" = "systemtests-pr" ]]; then
    run_test ${TESTCASE} systemtests-shared-brokered-pr || failure=$(($failure + 1))
    run_test ${TESTCASE} systemtests-shared-standard-pr || failure=$(($failure + 1))
    run_test ${TESTCASE} systemtests-isolated-pr || failure=$(($failure + 1))
elif [[ "${TEST_PROFILE}" = "systemtests-marathon" ]] || [[ "${TEST_PROFILE}" = "systemtests-upgrade" ]]; then
    run_test ${TESTCASE} ${TEST_PROFILE}|| failure=$(($failure + 1))
elif [[ "${TEST_PROFILE}" = "systemtests-release" ]]; then
    run_test ${TESTCASE} systemtests-shared-brokered-release || failure=$(($failure + 1))
    run_test ${TESTCASE} systemtests-shared-standard-release || failure=$(($failure + 1))
    run_test ${TESTCASE} systemtests-isolated-release || failure=$(($failure + 1))
else
    run_test ${TESTCASE} systemtests-shared-brokered || failure=$(($failure + 1))
    run_test ${TESTCASE} systemtests-shared-standard || failure=$(($failure + 1))
    run_test ${TESTCASE} systemtests-isolated || failure=$(($failure + 1))
fi

#stop system resources logging
info "process for checking system resources with PID: ${STATS_PID} will be killed"
kill -9 ${STATS_PID}

#stop docker logging
info "process for syncing docker logs with PID: ${LOGS_PID} will be killed"
kill -9 ${LOGS_PID}
categorize_docker_logs "${DOCKER_LOG_DIR}" || true
print_images

if [[ ${failure} -gt 0 ]]; then
    err_and_exit "Systemtests failed"
elif [[ "${TEST_PROFILE}" != "systemtests-upgrade" ]]; then
    teardown_test ${KUBERNETES_NAMESPACE}
fi
info "End of run_test_components.sh"
