# Golden path notes

Values listed here are kept on migration; say why, so the next person knows whether it's still needed.

| File | Shared workflow | Preserve (and why) |
|---|---|---|
| `pull-request.yml` | `ci-pr-checks-lib.yml` | all defaults (Java 25); trigger type `edited` deliberately left out, otherwise every ticked checklist box reruns the full build |
| `pr-check-checklist.yml` | none (repo-specific) | runs on `edited` and only fails while the PR body has unticked boxes |
| `release.yml` | `ci-build-publish-lib.yml` | all defaults (Java 25); `package-version` from the release tag, `deployment-repository` this repo |

Secrets: `secrets: inherit` everywhere. `dependabot.yml` needs no registry while the library has no internal Maven
dependencies.
Out of scope: none.
