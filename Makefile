GRADLE := ./gradlew
BASE_VERSION := $(shell awk -F= '/^mod_version/ {gsub(/ /,"",$$2); print $$2}' gradle.properties)
CONCORD_DIR ?= ../concord

.PHONY: help build clean test jar run-client run-server gen-sources refresh-deps version release site site-serve sync

help:
	@echo "Targets:"
	@echo "  build        Compile, test, and assemble the mod jar"
	@echo "  jar          Print the path to the built primary jar"
	@echo "  test         Run JUnit tests"
	@echo "  run-client   Launch a dev Minecraft client with the mod loaded"
	@echo "  run-server   Launch a dev Minecraft server with the mod loaded"
	@echo "  gen-sources  Generate Minecraft sources for IDE navigation"
	@echo "  refresh-deps Refresh Gradle dependencies"
	@echo "  clean        Remove build outputs"
	@echo "  version      Print the base and git-derived computed version"
	@echo "  release      Cut a release (usage: make release BUMP=patch|minor|major [NO_PUSH=1])"
	@echo "  site         Build the website from site/ with the shared concord template"
	@echo "  site-serve   Build and serve the website locally with live reload"
	@echo "  sync         Refresh .ai/skills + .ai/commands from the concord checkout (CONCORD_DIR=../concord)"

build:
	$(GRADLE) build

jar: build
	@ls -1 build/libs/prosperity-*.jar 2>/dev/null | grep -Ev -- '-(sources|dev|javadoc)\.jar$$' | head -1

test:
	$(GRADLE) test

run-client:
	$(GRADLE) runClient

run-server:
	$(GRADLE) runServer

gen-sources:
	$(GRADLE) genSources

refresh-deps:
	$(GRADLE) --refresh-dependencies build

clean:
	$(GRADLE) clean

version:
	@echo "base:     $(BASE_VERSION)"
	@echo "computed: $$($(GRADLE) -q printVersion 2>/dev/null || echo '(gradle failed; falling back to base)')"

release:
	@test -n "$(BUMP)" || (echo "Usage: make release BUMP=patch|minor|major [NO_PUSH=1]" && exit 1)
	@scripts/release.sh $(BUMP) $(if $(NO_PUSH),--no-push,)

site:
	SITE_DIR=$(PWD)/site npx -y @11ty/eleventy@3.0.0 --config=../concord/template/eleventy.config.cjs --input=../concord/template/src --output=_site

site-serve:
	SITE_DIR=$(PWD)/site npx -y @11ty/eleventy@3.0.0 --config=../concord/template/eleventy.config.cjs --input=../concord/template/src --output=_site --serve

sync:
	@test -d $(CONCORD_DIR)/.ai/skills || { echo "concord checkout not found at $(CONCORD_DIR) (set CONCORD_DIR=...)"; exit 1; }
	rsync -a --delete $(CONCORD_DIR)/.ai/skills/ .ai/skills/
	rsync -a --delete $(CONCORD_DIR)/.ai/commands/ .ai/commands/
	@git -C $(CONCORD_DIR) rev-parse HEAD > .ai/skills/.concord-rev
	@echo "synced .ai/skills + .ai/commands from concord @ $$(git -C $(CONCORD_DIR) rev-parse --short HEAD)"
