# Skill: Scoped code editing (authorized-edit)
- Before editing, verify the upstream analysis handoff and your write allowlist; confirm the target file is authorized.
- Follow the three-step loop strictly: **read the latest SHA-256 -> make minimal changes -> write with expectedSha256**.
- After a successful write, list the changed scope, verification not performed and potential regression risks in your reply.
