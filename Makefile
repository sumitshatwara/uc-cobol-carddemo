# CardDemo legacy estate: build and run the batch chains locally.
# Everything executes inside the `estate` Docker Compose service (GnuCOBOL 3,
# Open COBOL ESQL) against the `db` service (PostgreSQL 15).
#
#   make up                     start the containers (builds the image once)
#   make build                  compile app/cbl + Db2 sub-application into ./loadlib
#   make run CHAIN=posting      run one chain on its first fixture case (or CASE=...)
#   make record [CHAIN=..] [CASE=..]   (re)write golden outputs under fixtures/*/*/expected
#   make parity [CHAIN=..] [CASE=..]   rerun chains and compare field by field -> work/parity/report.md
#   make parity-naive CASE=i02-tie-half-cent   half-even Python reference vs golden (fails on the tie)
#   make chain-graph / chain-graph-check       regenerate / verify docs/chain-graph.md
#   make reset                  drop datasets, loadlib, work/ and the database volume

# run the estate container as the invoking user so bind-mounted loadlib/, work/,
# datasets/ stay writable on any host (CI runners are not uid 1000)
export UID := $(shell id -u)
export GID := $(shell id -g)

COMPOSE ?= docker compose
EXEC     = $(COMPOSE) exec -T estate
CHAINS  ?= posting interest trantype-update trantype-dynamic
CHAIN   ?=
CASE    ?=
PY       = python3

.PHONY: up down build run record parity parity-naive chain-graph chain-graph-check reset baseline shell \
        java-db java-build java-gen-check java-fixtures-check parity-java test-unit ui-install ui-build serve test-e2e test-all

up:
	$(COMPOSE) up -d --build --wait

down:
	$(COMPOSE) down

build: up
	$(EXEC) tools/build.sh

shell:
	$(COMPOSE) exec estate bash

# ---------------------------------------------------------------- chains
define for_each_case
	@set -e; \
	chains="$(if $(CHAIN),$(CHAIN),$(CHAINS))"; \
	for chain in $$chains; do \
	  cases="$(if $(CASE),$(CASE),$$(ls fixtures/$$chain))"; \
	  for case in $$cases; do $(1); done; \
	done
endef

run:
	@test -n "$(CHAIN)" || { echo "usage: make run CHAIN=<posting|interest|trantype-update> [CASE=<case>]"; exit 2; }
	@case="$(if $(CASE),$(CASE),$$(ls fixtures/$(CHAIN) | head -1))"; \
	$(EXEC) $(PY) tools/parity/record.py $(CHAIN) $$case --out work/run/$(CHAIN)/$$case -v

record:
	$(call for_each_case,$(EXEC) $(PY) tools/parity/record.py $$chain $$case --out fixtures/$$chain/$$case/expected)

parity:
	@rm -f work/parity/report.md; mkdir -p work/parity; fail=0; \
	chains="$(if $(CHAIN),$(CHAIN),$(CHAINS))"; \
	for chain in $$chains; do \
	  cases="$(if $(CASE),$(CASE),$$(ls fixtures/$$chain))"; \
	  for case in $$cases; do \
	    $(EXEC) $(PY) tools/parity/record.py $$chain $$case --out work/parity/$$chain/$$case/actual > /dev/null || { echo "$$chain/$$case: chain run failed"; fail=1; continue; }; \
	    $(EXEC) $(PY) tools/parity/compare.py $$chain $$case --actual work/parity/$$chain/$$case/actual --report work/parity/report.md || fail=1; \
	  done; \
	done; \
	echo "report: work/parity/report.md"; exit $$fail

parity-naive:
	@test -n "$(CASE)" || { echo "usage: make parity-naive CASE=<interest case>"; exit 2; }
	@rm -f work/parity/report-naive.md; mkdir -p work/parity; \
	$(EXEC) $(PY) tools/parity/naive_ref.py interest $(CASE) --out work/parity/interest/$(CASE)/naive && \
	$(EXEC) $(PY) tools/parity/compare.py interest $(CASE) --actual work/parity/interest/$(CASE)/naive --report work/parity/report-naive.md --label "naive half-even reference"; \
	rc=$$?; echo "report: work/parity/report-naive.md"; exit $$rc

# ------------------------------------------------------------ discovery graph
chain-graph:
	$(PY) tools/chaingraph/gen_chain_graph.py --out docs/chain-graph.md

chain-graph-check:
	$(PY) tools/chaingraph/gen_chain_graph.py --check docs/chain-graph.md

baseline:
	$(EXEC) tools/build.sh

# ==================================================================== Java 21 target
# java/ (Maven multi-module, Spring Boot 3.3 + Spring Batch) runs on the host JDK 21 and
# reaches the estate's PostgreSQL on localhost:$(CARDDEMO_DB_PORT), database carddemo_java.
# Slices never edit this section: a module that implements batch chains lists them in
# java/<module>/parity-chains and `make parity-java` picks it up.
#
#   make test-unit                         JUnit (all modules)
#   make parity-java [CHAIN=..] [CASE=..]  run the Java chain(s) on every fixture case and
#                                          compare with tools/parity/compare.py -> work/parity/report-java.md
#   make test-e2e                          Playwright journeys on the operations console (ui/)
#   make serve                             Spring Boot service + console on http://localhost:8080
#   make test-all                          the three layers: unit, regression, functional

JAVA_HOME ?= $(firstword $(wildcard /usr/lib/jvm/java-21-openjdk-amd64))
ifneq ($(JAVA_HOME),)
export JAVA_HOME
endif
MVN      ?= mvn
CARDDEMO_DB_PORT ?= 55432
export CARDDEMO_DB_PORT
JAVA_JAR  = java/app/target/carddemo-app.jar
JAVA_CLI  = java -jar $(JAVA_JAR) --spring.main.web-application-type=none
JAVA_CHAINS = $(shell cat java/*/parity-chains 2>/dev/null | grep -v '^\#' | tr -s ' \n' ' ')

java-db: up
	@$(COMPOSE) exec -T db psql -U carddemo -d carddemo -tAc "SELECT 1 FROM pg_database WHERE datname='carddemo_java'" | grep -q 1 || \
	  $(COMPOSE) exec -T db psql -U carddemo -d carddemo -qc "CREATE DATABASE carddemo_java TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C'"

java-build:
	$(MVN) -f java/pom.xml -B -q -DskipTests package

java-gen-check:
	$(PY) tools/javagen/gen_vsam.py --check

java-fixtures-check: java-db java-build
	$(JAVA_CLI) --carddemo.cli.mode=check-fixtures

parity-java: java-db java-build
	@chains="$(if $(CHAIN),$(CHAIN),$(JAVA_CHAINS))"; \
	test -n "$$chains" || { echo "no Java chain modules yet (java/*/parity-chains is empty)"; exit 1; }; \
	mkdir -p work/parity; : > work/parity/report-java.md; fail=0; \
	for chain in $$chains; do \
	  cases="$(if $(CASE),$(CASE),$$(ls fixtures/$$chain))"; \
	  $(JAVA_CLI) --carddemo.cli.mode=run --carddemo.cli.chain=$$chain \
	    --carddemo.cli.cases=$$(echo $$cases | tr ' ' ',') --carddemo.cli.out=work/java | grep '^java ' || fail=1; \
	  for case in $$cases; do \
	    $(EXEC) $(PY) tools/parity/compare.py $$chain $$case --actual work/java/$$chain/$$case/actual \
	      --report work/parity/report-java.md --label java || fail=1; \
	  done; \
	done; \
	echo "report: work/parity/report-java.md"; exit $$fail

test-unit:
	$(MVN) -f java/pom.xml -B test

ui-install:
	cd ui && (test -d node_modules || npm ci --no-audit --no-fund)

ui-build: ui-install
	cd ui && npm run build

serve: java-db java-build ui-build
	java -jar $(JAVA_JAR)

test-e2e: java-db java-build ui-build
	cd ui && npx playwright test

test-all: test-unit parity-java test-e2e

reset:
	rm -rf datasets loadlib work
	$(COMPOSE) down -v
