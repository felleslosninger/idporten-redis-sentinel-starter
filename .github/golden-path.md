# Golden path notes

Values listed here are kept on migration; say why, so the next person knows whether it's still needed.

| File | Shared workflow | Preserve (and why) |
|---|---|---|
| `pull-request.yml` | `ci-pr-checks-lib.yml` | `java-version: 21` — the library targets Java 21 (pom.xml) so applications still on 21 can use it |
| `release.yml` | `ci-build-publish-lib.yml` | `java-version: 21` — same reason; `package-version` from the release tag, `deployment-repository` this repo |

Secrets: `secrets: inherit` everywhere. `dependabot.yml` needs no registry while the library has no internal Maven
dependencies.
Out of scope: none.
