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

The commit hook checks author and committer identity.

The push hook checks only the commits newly introduced to the destination remote, plus annotated-tag
identity. Already-published ancestors are not revalidated: rejecting a later push cannot unpublish a
commit that is already on the remote, and revalidating the whole ancestry would make every branch based
on an existing history permanently unpushable.

A local hook cannot see a server-side rewrite. A GitHub squash merge creates a new commit on the server
and sets its author from the account, which has already produced one public non-pseudonymous commit in
this repository (1d38803, author `Yunzenn <yunzenn@qq.com>`). Account-level Email privacy settings are
therefore the first line of defence for pull-request merges, and these hooks are the second. Keep
"Keep my email addresses private" and "Block command line pushes that expose my email" enabled.

Do not bypass these hooks.
Hooks are a local safeguard, not server-side enforcement or a file-content scanner.
Review staged files for personal information before every push.

The initial four commits and two baseline tags were rewritten for privacy. Existing
clones should re-clone or carefully migrate unpublished work; do not merge the old
history back into main. Local recovery bundles contain the original private metadata
and must never be uploaded. History rewriting cannot recall copies already downloaded.
