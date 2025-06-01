#!/usr/bin/env bash

./support/check_env.sh

cd ..
export SPRING_PROFILES_ACTIVE=web,severance
mvn -P agent-examples-kotlin -Dmaven.test.skip=true spring-boot:run
