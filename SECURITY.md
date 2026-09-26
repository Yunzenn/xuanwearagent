# Security Policy

This project drives a physical Android watch: it captures microphone audio, holds long-term personal
memory, talks to a backend, and — once G3 lands — operates the device through typed tools. That makes
some reports more sensitive than in an ordinary app repository.

## Do not open a public issue for

```text
authentication or bootstrap bypass
token, credential or identity leakage
remote control of the device or of a tool call
privilege escalation, or a tool reachable without its confirmation policy
prompt injection that results in a device action
memory disclosure: one user's memory readable by another
anything that would let a third party observe microphone audio or location
```

Report those privately through GitHub's **Private vulnerability reporting** on this repository
(Security → Report a vulnerability). If that is unavailable, contact the maintainer listed in
`.github/CODEOWNERS` without disclosing details in public.

## In scope

```text
:app, :core-protocol, :core-audio, :core-live2d and the build configuration
the documented contract with the Xiaozhi backend
```

## Out of scope

```text
the Live2D Cubism SDK itself (proprietary, not in this repository - report upstream)
the upstream Xiaozhi server implementation
issues that require a rooted or physically compromised device
```

## What we will not do

We will not bypass a confirmation policy to make a demo smoother, and we will not remove the
pseudonymous-author or privacy guards to unblock a push. Those guards exist because this repository is
public and holds a person's private life.