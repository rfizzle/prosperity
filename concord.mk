# Concord shared Makefile targets -- HUB-OWNED. Do not edit in a member repo: the hub's propagate/
# sync overwrites this file verbatim, so a local edit is lost on the next sync PR. Change it in
# rfizzle/concord (propagate/concord.mk) and let the sync PR carry it to every member.
#
# A member's Makefile includes it and keeps only its own targets:
#
#     include concord.mk
#
#     .PHONY: my-target
#     my-target: ## One-line description shown by `make help`
#     	$(GRADLE) myTask
#
# The mod id is read from gradle.properties (archives_base_name), so this file is byte-identical in
# every member. `scripts/check-makefile-targets.py` verifies the member includes it and does not
# redefine a target owned here. `make help` lists every target that carries a trailing `## ` note,
# this file's first.

GRADLE ?= ./gradlew
CONCORD_DIR ?= ../concord
MOD_ID := $(shell awk -F= '/^archives_base_name/ {gsub(/[ \t\r]/,"",$$2); print $$2}' gradle.properties)
BASE_VERSION := $(shell awk -F= '/^mod_version/ {gsub(/[ \t\r]/,"",$$2); print $$2}' gradle.properties)

.DEFAULT_GOAL := help

.PHONY: help build jar test coverage ci run-client run-server run-datagen gen-sources refresh-deps clean version release site site-serve sync

help: ## Show this menu
	@echo "Targets:"
	@grep -hE '^[a-zA-Z][a-zA-Z0-9_-]*:.*## ' concord.mk $(filter-out concord.mk,$(MAKEFILE_LIST)) | awk -F':.*## ' '{printf "  %-13s %s\n", $$1, $$2}'

build: ## Compile, test, and assemble the mod jar
	$(GRADLE) build

jar: build ## Print the path to the built primary jar
	@ls -1 build/libs/$(MOD_ID)-*.jar 2>/dev/null | grep -Ev -- '-(sources|dev|javadoc)\.jar$$' | head -1

test: ## Run JUnit tests
	$(GRADLE) test

# Merged unit + gametest coverage -- the mod's real coverage number. Report lands in
# build/reports/jacoco/jacocoMergedReport/ (HTML + XML).
coverage: ## Run unit tests + gametests and write the merged coverage report
	$(GRADLE) test runGametest jacocoMergedReport
	@echo "report: build/reports/jacoco/jacocoMergedReport/html/index.html"
	@echo "note:   build/reports/jacoco/test/ is unit-tests-only — not the mod's coverage number"

# Exactly what .github/workflows/mod-ci.yml runs, in the same order, so a green `make ci` predicts a
# green CI job. The listing check needs a concord checkout (CONCORD_DIR, default ../concord) and the
# art/audio verifiers need ffmpeg; the workflow installs it, here it is on you. The workflow's
# coverage-summary and artifact-upload steps are reporting, not checks, and are not repeated. make
# stops at the first failing step, where the workflow still runs the coverage report after one.
ci: ## Run exactly what CI runs: listing check, art/audio verify, build, gametests, datagen idempotence, merged coverage
	@test -d $(CONCORD_DIR)/scripts || { echo "concord checkout not found at $(CONCORD_DIR) (set CONCORD_DIR=...)"; exit 1; }
	python3 $(CONCORD_DIR)/scripts/check-listing-claims.py --members-file $(CONCORD_DIR)/members.json
	python3 .ai/skills/mc-textures/scripts/glyph.py --verify-all
	python3 .ai/skills/mc-audio/scripts/sfx.py --verify-all
	$(GRADLE) build --no-daemon --stacktrace --warning-mode=all
	$(GRADLE) runGametest --no-daemon --stacktrace --warning-mode=all
	$(GRADLE) verifyDatagenIdempotent --no-daemon --stacktrace
	$(GRADLE) jacocoMergedReport --no-daemon --stacktrace

run-client: ## Launch a dev Minecraft client with the mod loaded
	$(GRADLE) runClient

run-server: ## Launch a dev Minecraft server with the mod loaded
	$(GRADLE) runServer

# Regenerates src/main/generated/. Commit the result -- `./gradlew verifyDatagenIdempotent` fails
# the build when the committed tree does not match a fresh run.
run-datagen: ## Run Fabric data generation into src/main/generated
	$(GRADLE) runDatagen

gen-sources: ## Generate Minecraft sources for IDE navigation
	$(GRADLE) genSources

refresh-deps: ## Refresh Gradle dependencies
	$(GRADLE) --refresh-dependencies build

clean: ## Remove build outputs
	$(GRADLE) clean

version: ## Print the base and git-derived computed version
	@echo "base:     $(BASE_VERSION)"
	@echo "computed: $$($(GRADLE) -q printVersion 2>/dev/null || echo '(gradle failed; falling back to base)')"

release: ## Tag v<version> and push it to trigger the release (usage: make release VERSION=X.Y.Z[-pre] [NO_PUSH=1])
	@test -n "$(VERSION)" || (echo "Usage: make release VERSION=X.Y.Z[-pre] [NO_PUSH=1]" && exit 1)
	python3 scripts/concord-release.py $(VERSION) $(if $(NO_PUSH),--no-push)

site: ## Build the website from site/ with the shared concord template
	node ../concord/template/scripts/gen-changelog.cjs $(PWD)/site
	SITE_DIR=$(PWD)/site npx -y @11ty/eleventy@3.0.0 --config=../concord/template/eleventy.config.cjs --input=../concord/template/src --output=_site

site-serve: ## Build and serve the website locally with live reload
	node ../concord/template/scripts/gen-changelog.cjs $(PWD)/site
	SITE_DIR=$(PWD)/site npx -y @11ty/eleventy@3.0.0 --config=../concord/template/eleventy.config.cjs --input=../concord/template/src --output=_site --serve

sync: ## Refresh .ai/skills + .ai/commands + .ai/agents from the concord checkout (CONCORD_DIR=../concord)
	@test -d $(CONCORD_DIR)/.ai/skills || { echo "concord checkout not found at $(CONCORD_DIR) (set CONCORD_DIR=...)"; exit 1; }
	rsync -a --delete $(CONCORD_DIR)/.ai/skills/ .ai/skills/
	rsync -a --delete $(CONCORD_DIR)/.ai/commands/ .ai/commands/
	rsync -a --delete $(CONCORD_DIR)/.ai/agents/ .ai/agents/
	@git -C $(CONCORD_DIR) rev-parse HEAD > .ai/skills/.concord-rev
	@echo "synced .ai/skills + .ai/commands + .ai/agents from concord @ $$(git -C $(CONCORD_DIR) rev-parse --short HEAD)"
