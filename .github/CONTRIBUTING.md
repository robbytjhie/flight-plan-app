# Contributing & Git Workflow

## Branch Strategy (GitHub Flow + protection)

```
main          ← production-ready, protected, deploys to prod via tag
develop       ← integration branch, deploys to staging on merge
feature/*     ← all new work branches from develop
fix/*         ← bug fixes branch from develop (or main for hotfixes)
hotfix/*      ← emergency fixes branch from main
release/*     ← release candidates, branch from develop
```

## Commit Message Convention (Conventional Commits)

```
<type>(<scope>): <short summary>

[optional body]
[optional footer: JIRA-123]
```

Types: `feat` `fix` `refactor` `test` `chore` `docs` `ci` `perf` `security`

Examples:
```
feat(cache): add @PostConstruct eager cache population on startup
fix(security): map ConstraintViolationException to HTTP 400
security(headers): force HSTS on all requests via AnyRequestMatcher
test(controller): import SecurityConfig into WebMvcTest slice
ci: add GitHub Actions pipeline with Sonar and ECR deploy
```

## Pull Request Rules
- PRs must target `develop` (or `main` for hotfixes)
- All CI checks must pass before merge
- Code coverage must remain ≥ 90% (enforced by JaCoCo + Sonar gate)
- At least 1 approving review required
- Squash merge preferred to keep history clean
