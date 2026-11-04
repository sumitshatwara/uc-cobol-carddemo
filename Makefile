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

.PHONY: up down build run record parity parity-naive chain-graph chain-graph-check reset baseline shell

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

reset:
	rm -rf datasets loadlib work
	$(COMPOSE) down -v

# ------------------------------------------------------------ Java foundation
JAVA_HOME ?= /usr/lib/jvm/java-21-openjdk-amd64
export JAVA_HOME
export PYTHONDONTWRITEBYTECODE := 1
JAVA_COMPOSE = $(COMPOSE) -f docker-compose.yml -f docker-compose.java.yml

.PHONY: java-db java-build test-unit parity-java java-run ui-install ui-dev ui-build test-functional test-all

java-db:
	$(JAVA_COMPOSE) up -d --wait db
	@$(JAVA_COMPOSE) exec -T db sh -c 'psql -U carddemo -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='\''carddemo_java'\''" | grep -q 1 || createdb -U carddemo -O carddemo carddemo_java'

java-build:
	JAVA_HOME=$(JAVA_HOME) mvn -U -q -f java/pom.xml -DskipTests package

test-unit:
	@if $(JAVA_COMPOSE) exec -T db psql -U carddemo -d carddemo_java -c 'SELECT 1' >/dev/null 2>&1; then \
	  CARDDEMO_DB_TESTS=true JAVA_HOME=$(JAVA_HOME) mvn -U -f java/pom.xml test; \
	else \
	  JAVA_HOME=$(JAVA_HOME) mvn -U -f java/pom.xml test; \
	fi

parity-java: java-db java-build
	@set -e; \
	handlers=$$(java -jar java/app/target/app-1.0.0-SNAPSHOT.jar --logging.level.root=OFF --carddemo.cli=list-chains); \
	if [ -z "$$handlers" ] && [ -z "$(CHAIN)" ]; then echo "no Java chains registered yet"; exit 0; fi; \
	mkdir -p work/parity; rm -f work/parity/report-java.md; \
	output=$$(java -jar java/app/target/app-1.0.0-SNAPSHOT.jar --logging.level.root=OFF --carddemo.cli=run-all $(if $(CHAIN),--carddemo.chain=$(CHAIN),)); \
	printf '%s\n' "$$output" > work/parity/java-cases.txt; \
	passed=0; total=0; \
	while IFS=' ' read -r chain case_name; do \
	  [ -n "$$chain" ] || continue; \
	  total=$$((total + 1)); \
	  if $(PY) tools/parity/compare.py $$chain $$case_name --actual work/parity-java/$$chain/$$case_name/actual --label java --report work/parity/report-java.md; then passed=$$((passed + 1)); fi; \
	done < work/parity/java-cases.txt; \
	echo "$$passed/$$total cases passed"; \
	test "$$passed" -eq "$$total"

java-run: java-build
	JAVA_HOME=$(JAVA_HOME) java -jar java/app/target/app-1.0.0-SNAPSHOT.jar

ui-install:
	cd ui && npm ci

ui-dev:
	cd ui && npm run dev -- --host 0.0.0.0

ui-build:
	cd ui && npm run build

test-functional: java-db java-build ui-build
	cd ui && npx playwright test $(if $(SLICE),e2e/$(SLICE).spec.ts,)

test-all: java-db java-build test-unit ui-install ui-build test-functional parity-java
