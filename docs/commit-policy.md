# Commit policy

This fork uses small, reviewable, purposeful commits.

- **Frequency:** commit after each independently testable unit (protocol investigation/tests, transport implementation, UI/onboarding, CI/release packaging, documentation). Do not make arbitrary time-based or file-by-file commits. Prefer a commit whenever a coherent change passes its relevant local checks; fix known failures before advancing.
- **Messages:** use `type(scope): imperative summary` (lowercase type, no terminal full stop, aim for 72 characters or fewer). Allowed types are `feat`, `fix`, `test`, `refactor`, `ci`, `build`, `docs`, and `chore`. Example: `feat(relay): authenticate before daemon RPC`.
- **Commit contents:** one technical concern per commit. Commit implementation and closely related tests together; keep unrelated formatting or dependency upgrades separate. Document behaviour changes and security assumptions.
- **Verification:** record test and build results in pull requests. Never label an APK production-ready unless the release build, signing verification, and real-device connection smoke test have succeeded.
- **Secrets and safety:** never commit Factory API keys, session tokens, device pairing codes, private machine identifiers, signing keys, passwords, or binary samples containing credentials. Do not bypass secure relay transport or certificate verification to get a passing test.
- **Integration:** develop on a feature branch and open a pull request against `main`. Prefer fast-forward/rebase of reviewed commits; avoid force-pushing shared branches. Merge only after CI passes and the new connection path is verified.
