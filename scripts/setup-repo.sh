#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# setup-repo.sh — one-time script to initialise the Git repo, add remote,
# commit all source + devops files, and push the first branch.
#
# Run from the repo root (the directory containing flight-plan-backend/).
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REMOTE_URL="${1:-}"   # pass your GitHub remote URL as arg 1

if [[ -z "$REMOTE_URL" ]]; then
  echo "Usage: $0 git@github.com:<org>/flight-plan-backend.git"
  exit 1
fi

echo "==> Initialising Git repository..."
git init
git checkout -b main

echo "==> Copying .gitignore into repo root..."
cp devops/.gitignore .gitignore

echo "==> Copying DevOps files into place..."
# Dockerfile goes alongside pom.xml
cp devops/Dockerfile flight-plan-backend/Dockerfile

# GitHub Actions workflows
mkdir -p .github/workflows .github
cp devops/.github/workflows/ci-cd.yml      .github/workflows/ci-cd.yml
cp devops/.github/workflows/terraform.yml  .github/workflows/terraform.yml
cp devops/.github/CONTRIBUTING.md          .github/CONTRIBUTING.md

# Sonar properties (next to pom.xml)
cp devops/sonar/sonar-project.properties   flight-plan-backend/sonar-project.properties

# Terraform
cp -r devops/terraform terraform

echo "==> Adding sonar-maven-plugin to pom.xml..."
# (See README — add the sonar plugin snippet to pom.xml manually, or run
#  the sed command below if your pom.xml has not been modified yet)
# sed -i 's|</plugins>|'"$(cat devops/scripts/sonar-plugin-snippet.xml)"'\n</plugins>|' \
#   flight-plan-backend/pom.xml

echo "==> Staging all files..."
git add .

echo "==> Initial commit..."
git commit -m "chore: initial commit — Spring Boot flight-plan-backend with CI/CD

- Spring Boot 3.2, Java 17, Maven
- IM8-compliant security config (HSTS, CSP, CORS, input validation)
- Leader-elected cache via Redis distributed lock
- JaCoCo coverage enforcement (≥90% line, ≥85% branch)
- GitHub Actions: build → test → Sonar → ECR → ECS deploy
- Terraform: VPC, ECR, ECS Fargate, ALB, ElastiCache, IAM
- SonarCloud quality gate configuration"

echo "==> Creating develop branch..."
git checkout -b develop
git push -u origin develop

git checkout main
git push -u origin main

echo ""
echo "✅  Done. Next steps:"
echo "   1. Add GitHub repository secrets (see README — Secrets section)"
echo "   2. Configure SonarCloud project and set Quality Gate ≥ 90% coverage"
echo "   3. Set up GitHub Environments: 'staging' and 'production' (with required reviewers)"
echo "   4. Run: terraform init && terraform plan   (in /terraform)"
echo "   5. Push a feature branch and open a PR to trigger the first CI run"
