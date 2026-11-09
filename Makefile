.PHONY: build dev down logs load reset test acceptance kind
build:
	./scripts/compose.sh build server payment web
dev: build
	./scripts/compose.sh up -d --no-build --wait --wait-timeout 240 server web gateway checkout payment inventory
down:
	./scripts/compose.sh down
logs:
	./scripts/compose.sh logs -f --tail 50 server
load:
	mkdir -p reports/runtime
	LOADGEN_UID=$$(id -u) LOADGEN_GID=$$(id -g) ./scripts/compose.sh --profile load run --rm -T -e RATE=$${RATE:-20} -e DURATION=$${DURATION:-5m} loadgen
reset:
	python3 scripts/fault.py checkout
	python3 scripts/fault.py payment
	python3 scripts/fault.py inventory
test:
	./gradlew check -x :tests:acceptance:test --no-daemon
	TRACESCOPE_ACCEPTANCE=true ./gradlew :tests:acceptance:test --tests dev.tracescope.acceptance.HarnessPolicyTest --no-daemon
	npm --prefix web ci --no-fund --no-audit
	npm --prefix web run build
	npm --prefix web test
acceptance:
	./scripts/acceptance.sh
kind:
	KEEP_KIND=1 ./scripts/verify-kind.sh
