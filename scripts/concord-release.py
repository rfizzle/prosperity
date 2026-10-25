#!/usr/bin/env python3
"""Tag a Concord member release, behind the same gates the release workflow enforces.

Usage:
    python3 scripts/concord-release.py <X.Y.Z[-pre]> [--no-push]
    python3 scripts/concord-release.py --verify-tag vX.Y.Z

Release mode requires a clean tree on master that equals origin/master, a tag that
does not exist yet, a version greater than every existing v* tag by SemVer
precedence, and a green `ci / test` check on HEAD. It then creates an annotated
tag v<version> and pushes it (unless --no-push).

--verify-tag is the workflow entry point: it checks an already-pushed tag the same
way (commit reachable from origin/master, `ci / test` green on its commit, version
greater than every other v* tag) without creating or pushing anything.

Stdlib only. Synced verbatim from the concord hub into every member.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys

CI_CHECK = "ci / test"
SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)


class ReleaseError(Exception):
    """A guard failed; the message is shown to the user as-is."""


def parse_semver(text: str):
    """Return (major, minor, patch, pre-release identifiers) or raise ReleaseError."""
    m = SEMVER.match(text)
    if not m:
        raise ReleaseError(
            f"'{text}' is not a SemVer version (expected X.Y.Z, optionally -prerelease)"
        )
    pre = m.group(4).split(".") if m.group(4) else []
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), pre


def _cmp(a, b) -> int:
    return (a > b) - (a < b)


def compare_semver(a: str, b: str) -> int:
    """SemVer 2.0 precedence: -1, 0 or 1. Build metadata is ignored."""
    ma, mb = parse_semver(a), parse_semver(b)
    core = _cmp(ma[:3], mb[:3])
    if core:
        return core
    pa, pb = ma[3], mb[3]
    if not pa and not pb:
        return 0
    if not pa:
        return 1  # a stable release outranks its pre-releases
    if not pb:
        return -1
    for x, y in zip(pa, pb):
        xn, yn = x.isdigit(), y.isdigit()
        if xn and yn:
            c = _cmp(int(x), int(y))
        elif xn != yn:
            c = -1 if xn else 1  # numeric identifiers sort below alphanumeric
        else:
            c = _cmp(x, y)
        if c:
            return c
    return _cmp(len(pa), len(pb))


def default_runner(cmd: list[str]) -> tuple[int, str]:
    p = subprocess.run(cmd, capture_output=True, text=True)
    return p.returncode, (p.stdout if p.returncode == 0 else p.stdout + p.stderr)


class Release:
    def __init__(self, run=default_runner):
        self.run = run

    def out(self, *cmd: str) -> str:
        rc, text = self.run(list(cmd))
        if rc != 0:
            raise ReleaseError(f"`{' '.join(cmd)}` failed:\n{text.strip()}")
        return text.strip()

    def git(self, *args: str) -> str:
        return self.out("git", *args)

    def repo_slug(self) -> str:
        url = self.git("remote", "get-url", "origin")
        m = re.search(r"github\.com[:/]([^/\s]+)/([^/\s]+?)(?:\.git)?/?$", url)
        if not m:
            raise ReleaseError(f"cannot derive owner/repo from origin URL '{url}'")
        return f"{m.group(1)}/{m.group(2)}"

    def existing_versions(self) -> list[str]:
        """Versions of every SemVer v* tag, local and remote. Other v* tags are ignored."""
        names = set(self.git("tag", "-l", "v*").split())
        rc, text = self.run(["git", "ls-remote", "--tags", "origin", "refs/tags/v*"])
        if rc != 0:
            raise ReleaseError(f"cannot list remote tags:\n{text.strip()}")
        for line in text.splitlines():
            ref = line.split("\t")[-1].strip()
            if ref.startswith("refs/tags/") and not ref.endswith("^{}"):
                names.add(ref[len("refs/tags/"):])
        return [n[1:] for n in sorted(names) if n.startswith("v") and SEMVER.match(n[1:])]

    def require_greater(self, version: str, others: list[str]) -> None:
        for other in others:
            if compare_semver(version, other) <= 0:
                raise ReleaseError(
                    f"v{version} is not greater than existing tag v{other} "
                    "by SemVer precedence; pick a higher version"
                )

    def require_ci_green(self, sha: str) -> None:
        slug = self.repo_slug()
        text = self.out(
            "gh", "api", "--paginate", f"repos/{slug}/commits/{sha}/check-runs",
            "--jq", ".check_runs[] | {id, name, status, conclusion}",
        )
        runs = [json.loads(line) for line in text.splitlines() if line.strip()]
        mine = [r for r in runs if r.get("name") == CI_CHECK]
        if not mine:
            raise ReleaseError(
                f"no '{CI_CHECK}' check run on {sha[:10]}; wait for CI to start or push the commit first"
            )
        latest = max(mine, key=lambda r: r.get("id", 0))
        if latest.get("status") != "completed":
            raise ReleaseError(f"'{CI_CHECK}' on {sha[:10]} is still {latest.get('status')}; wait for it to finish")
        if latest.get("conclusion") != "success":
            raise ReleaseError(f"'{CI_CHECK}' on {sha[:10]} concluded {latest.get('conclusion')}, not success")

    def release(self, version: str, push: bool = True) -> str:
        parse_semver(version)
        tag = f"v{version}"
        if self.git("status", "--porcelain", "--untracked-files=all"):
            raise ReleaseError("working tree is not clean (commit, stash or remove changes, including untracked files)")
        branch = self.git("rev-parse", "--abbrev-ref", "HEAD")
        if branch != "master":
            raise ReleaseError(f"on branch '{branch}'; releases are tagged from master")
        self.git("fetch", "--tags", "origin")
        head = self.git("rev-parse", "HEAD")
        remote = self.git("rev-parse", "origin/master")
        if head != remote:
            raise ReleaseError("local master differs from origin/master; pull or push so HEAD == origin/master")
        rc, _ = self.run(["git", "rev-parse", "-q", "--verify", f"refs/tags/{tag}"])
        if rc == 0:
            raise ReleaseError(f"tag {tag} already exists locally")
        rc, text = self.run(["git", "ls-remote", "--tags", "origin", f"refs/tags/{tag}"])
        if rc != 0:
            raise ReleaseError(f"cannot list remote tags:\n{text.strip()}")
        if text.strip():
            raise ReleaseError(f"tag {tag} already exists on origin")
        self.require_greater(version, self.existing_versions())
        self.require_ci_green(head)
        self.git("tag", "-a", tag, "-m", tag)
        if push:
            self.git("push", "origin", tag)
            return f"Tagged and pushed {tag}"
        return f"Tagged {tag}; push it with: git push origin {tag}"

    def verify_tag(self, tag: str) -> str:
        if not tag.startswith("v"):
            raise ReleaseError(f"'{tag}' is not a v* tag")
        version = tag[1:]
        parse_semver(version)
        sha = self.git("rev-parse", f"{tag}^{{commit}}")
        rc, _ = self.run(["git", "merge-base", "--is-ancestor", sha, "origin/master"])
        if rc != 0:
            raise ReleaseError(f"{tag} ({sha[:10]}) is not on origin/master; release tags must come from master")
        self.require_greater(version, [v for v in self.existing_versions() if v != version])
        self.require_ci_green(sha)
        return f"{tag} passes the release gate"


def main(argv=None, run=default_runner) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("version", nargs="?", help="X.Y.Z[-pre] to tag")
    ap.add_argument("--no-push", action="store_true", help="create the tag locally only")
    ap.add_argument("--verify-tag", metavar="TAG", help="check an existing pushed tag (workflow gate)")
    args = ap.parse_args(argv)
    if not args.version and not args.verify_tag:
        ap.error("a version or --verify-tag is required")
    try:
        r = Release(run)
        if args.verify_tag:
            msg = r.verify_tag(args.verify_tag)
        else:
            msg = r.release(args.version.removeprefix("v"), push=not args.no_push)
    except ReleaseError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    print(msg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
