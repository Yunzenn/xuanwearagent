# Git identity policy

Project commits and annotated tags use `yunzenn`, with the GitHub privacy email
`209125778+Yunzenn@users.noreply.github.com`. Never use a personal name/email or
include local account paths in published reports. Preserve third-party copyright
and license notices unchanged.

After cloning, configure this repository before committing:

```sh
git config --local user.name yunzenn
git config --local user.email 209125778+Yunzenn@users.noreply.github.com
git config --local core.hooksPath .githooks
```

The commit hook checks author and committer identity. The push hook checks all
reachable commits and annotated tags being pushed. Do not bypass these hooks.
Hooks are a local safeguard, not server-side enforcement or a file-content scanner.
Review staged files for personal information before every push.

The initial four commits and two baseline tags were rewritten for privacy. Existing
clones should re-clone or carefully migrate unpublished work; do not merge the old
history back into main. Local recovery bundles contain the original private metadata
and must never be uploaded. History rewriting cannot recall copies already downloaded.
