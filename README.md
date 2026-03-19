# ✈ FlightPlan — Route Visualiser

A full-stack application that interrogates Flight Manager APIs to display flight routes on an interactive global map.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────┐
│                   USER BROWSER                                     │
│         React + Vite + Leaflet.js  (Node:3000 / nginx:80)         │
│   Flight List │ Search │ Map Display │ Route Detail Panel         │
└────────────────────────┬───────────────────────────────────────────┘
                         │  REST /api/*
┌────────────────────────▼───────────────────────────────────────────┐
│              Spring Boot Backend  (Java 17 / port 8080)           │
│  FlightController → FlightService → MockDataService / WebClient   │
│  Endpoints:                                                        │
│    GET /api/flights              → all flight plans               │
│    GET /api/flights/search       → callsign search                │
│    GET /api/flights/{callsign}   → single flight                  │
│    GET /api/route/{callsign}     → resolved route + polyline      │
│    GET /api/geopoints/airways    → airways list                   │
│    GET /api/geopoints/fixes      → fixes/waypoints list           │
└────────────────────────┬───────────────────────────────────────────┘
                         │  HTTP + apikey header
┌────────────────────────▼───────────────────────────────────────────┐
│           External Flight Manager APIs (or Mock data)             │
│    /flight-manager/displayAll                                     │
│    /geopoints/list/airways                                        │
│    /geopoints/list/fixes                                          │
└───────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────┐
│                 CI/CD Pipeline (GitHub Actions)                   │
│  Test → Build → Docker Push (GHCR) → kubectl Deploy → Verify     │
└───────────────────────────────────────────────────────────────────┘

┌─────────────────┐     ┌──────────────────┐
│   Kubernetes    │     │   Docker Compose  │
│   (Production)  │     │  (Local Dev)      │
│  2x backend pod │     │  backend:8080     │
│  2x frontend pod│     │  frontend:3000    │
│  Ingress + HPA  │     └──────────────────┘
└─────────────────┘
```

---

## Tech Stack

| Layer       | Technology                        | Rationale                                               |
|-------------|-----------------------------------|---------------------------------------------------------|
| Frontend    | React 18 + Vite                   | Fast builds, modern ecosystem, component-based UI       |
| Map         | Leaflet.js + react-leaflet        | Open source, lightweight, excellent for flight paths    |
| Backend     | Java 17 + Spring Boot 3.2         | Robust REST API, reactive WebClient for upstream calls  |
| HTTP Client | Spring WebFlux WebClient          | Non-blocking, reactive; better than RestTemplate        |
| Containerise| Docker (multi-stage builds)       | Alpine base → small images; layer caching in CI         |
| Orchestrate | Kubernetes (Deployment, HPA)      | Rolling updates, autoscaling, health probes             |
| CI/CD       | GitHub Actions                    | Native GitHub integration, free for public repos        |
| Registry    | GitHub Container Registry (GHCR)  | Free, integrated with Actions, no separate account      |

---

## Quick Start (Local — Docker Compose)

**Prerequisites:** Docker + Docker Compose

```bash
git clone https://github.com/YOUR_ORG/flight-plan.git
cd flight-plan

# Optional: set real API credentials
cp .env.example .env
# Edit .env with real FLIGHT_API_BASE_URL and FLIGHT_API_KEY

# Start both services
docker compose up --build

# Open in browser
open http://localhost:3000
```

The app runs in **mock mode** by default (no API key required). It ships with 8 realistic flight routes across Asia-Pacific and beyond.

---

## Local Development (Without Docker)

### Backend
```bash
cd backend
# Requires: Java 17+, Maven 3.9+
mvn spring-boot:run
# API available at http://localhost:8080/api
```

### Frontend
```bash
cd frontend
# Requires: Node 20+
npm install
npm run dev
# UI available at http://localhost:3000
# Vite proxies /api/* → localhost:8080
```

---

## API Reference

| Method | Endpoint                      | Description                              |
|--------|-------------------------------|------------------------------------------|
| GET    | `/api/flights`                | List all flight plans                    |
| GET    | `/api/flights/search?callsign=SIA` | Search by callsign (partial match)  |
| GET    | `/api/flights/{callsign}`     | Get single flight plan                   |
| GET    | `/api/route/{callsign}`       | Resolved route with lat/lon polyline     |
| GET    | `/api/geopoints/airways`      | All airways geopoints                    |
| GET    | `/api/geopoints/fixes`        | All fix/waypoint geopoints               |
| GET    | `/api/health`                 | Health check                             |

**Example:**
```bash
curl http://localhost:8080/api/route/SIA200
# Returns: { callsign, departureAerodrome, destinationAerodrome,
#             aircraftType, routePoints:[{name, lat, lon, type, seqNum}],
#             polyline:[[lat,lon],...] }
```

---

## Connecting to Real APIs

Once you have your API credentials:

1. Update `.env`:
```
FLIGHT_API_BASE_URL=https://actual-api-base.com
FLIGHT_API_KEY=your-real-api-key
```

2. The backend auto-detects real vs mock mode based on the key. No code changes needed.

3. The `FlightService` will call the live endpoints and fall back to mock data if unreachable.

---

## CI/CD Pipeline

```
Push to main
    │
    ├── test-backend     (Maven test + build JAR)
    ├── test-frontend    (npm ci + lint + test + build)
    │
    └── build-push-images (on success, main branch only)
            │  docker build --push → ghcr.io/ORG/flightplan-backend:sha-XXXX
            │  docker build --push → ghcr.io/ORG/flightplan-frontend:sha-XXXX
            │
            └── deploy
                    kubectl apply -f k8s/
                    kubectl set image ... (pinned SHA tag)
                    kubectl rollout status (waits for healthy)
```

### GitHub Secrets Required

| Secret       | Description                                                     |
|--------------|-----------------------------------------------------------------|
| `KUBE_CONFIG`| Base64-encoded kubeconfig: `cat ~/.kube/config \| base64 -w 0` |

---

## Kubernetes Deployment

**Prerequisites:** kubectl configured, nginx-ingress-controller installed

```bash
# Apply all manifests
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/secret.yaml      # Update with real credentials first!
kubectl apply -f k8s/backend.yaml
kubectl apply -f k8s/frontend.yaml
kubectl apply -f k8s/ingress.yaml
kubectl apply -f k8s/hpa.yaml

# Check status
kubectl get all -n flightplan

# View logs
kubectl logs -l app=flightplan-backend -n flightplan -f
```

**Update the Ingress host** in `k8s/ingress.yaml` from `flightplan.example.com` to your real domain.

### Kubernetes Features Used
- **Rolling updates** — zero-downtime deploys (maxUnavailable: 0)
- **HPA** — autoscale backend 2→6 pods on CPU/memory pressure
- **Health probes** — liveness + readiness on `/api/health` and `/health`
- **Resource limits** — CPU/memory requests + limits on all containers
- **Secrets** — API credentials mounted as env vars, never in images

---

## Project Structure

```
flight-plan/
├── backend/                        # Java Spring Boot
│   ├── src/main/java/com/flightplan/
│   │   ├── FlightPlanApplication.java
│   │   ├── config/AppConfig.java        # CORS + WebClient
│   │   ├── controller/FlightController.java
│   │   ├── service/
│   │   │   ├── FlightService.java       # Core business logic
│   │   │   └── MockDataService.java     # Stub data
│   │   └── model/
│   │       ├── FlightPlan.java
│   │       ├── GeoPoint.java
│   │       └── FlightRoute.java
│   ├── src/main/resources/application.yml
│   ├── Dockerfile
│   └── pom.xml
│
├── frontend/                       # React + Vite
│   ├── src/
│   │   ├── App.jsx                 # Root layout
│   │   ├── index.css               # Aviation dark theme
│   │   ├── components/
│   │   │   ├── FlightMap.jsx       # Leaflet map
│   │   │   ├── FlightList.jsx      # Sidebar list
│   │   │   ├── FlightDetail.jsx    # Route info panel
│   │   │   └── SearchBar.jsx       # Callsign search
│   │   ├── hooks/useFlights.js     # Data fetching hooks
│   │   └── services/api.js         # Axios API client
│   ├── Dockerfile
│   ├── nginx.conf
│   └── package.json
│
├── k8s/                            # Kubernetes manifests
│   ├── namespace.yaml
│   ├── secret.yaml                 # ⚠ gitignored — add your credentials
│   ├── backend.yaml
│   ├── frontend.yaml
│   ├── ingress.yaml
│   └── hpa.yaml
│
├── .github/workflows/ci-cd.yml    # GitHub Actions pipeline
├── docker-compose.yml             # Local development
├── .env.example                   # Env template
└── README.md
```

---

## Design Decisions

**Why Spring WebFlux WebClient instead of RestTemplate?**
WebClient is non-blocking and reactive, which is better suited for I/O-bound work like HTTP API calls. It also has first-class support in Spring Boot 3.x.

**Why Vite instead of Create React App?**
Vite offers dramatically faster HMR (< 50ms) and smaller production bundles via native ES modules.

**Why Leaflet over Mapbox/Google Maps?**
Leaflet is fully open-source with no API key required, making it ideal for a self-contained demo. The tile layer uses OpenStreetMap. It also integrates well with react-leaflet.

**Why GHCR over Docker Hub?**
GitHub Container Registry is free, integrated natively with GitHub Actions (uses `GITHUB_TOKEN`), and requires no separate account setup.

**Why mock-first approach?**
The real API uses placeholder URLs in the spec. The mock service mirrors the exact JSON structure so the frontend and route logic can be fully developed and tested without live credentials. Swapping to live data requires only env vars — no code changes.

---

## AI-Assisted Development Disclosure

AI-assisted tools were used to accelerate implementation and learning. All suggestions were reviewed and adapted to fit this codebase, and changes were validated by running tests/coverage gates and verifying behaviour in the deployed environment.

- **Cursor (AI coding assistant in IDE)**: used for code navigation, refactoring assistance, and drafting implementation/test changes across frontend and backend.
- **Claude**: used to understand existing logic and workflows (e.g. tracing data flow, clarifying CI/CD pipeline intent, and reasoning about issues such as duplication and coverage drops).
- **ChatGPT & Gemini**: used for learning and quick reference on framework/tooling patterns (Spring Boot, React/Leaflet, GitHub Actions), plus brainstorming edge cases and test scenarios.

**What I validated manually**

- **Correctness**: checked endpoints and UI behaviour (including route selection and the alternate route toggle) via browser DevTools and direct API responses.
- **Quality gates**: confirmed unit/integration tests pass and that JaCoCo / frontend coverage thresholds are met.
- **Security & reliability**: ensured secrets are not hardcoded, reviewed input validation/sanitisation, and checked CI scan/report outputs for regressions.


---

## Code Quality (SonarCloud)

Every push runs a SonarCloud quality gate. The build **blocks** if any of these thresholds are missed:

| Metric | Threshold |
|--------|-----------|
| Line coverage | ≥ 90% |
| Branch coverage | ≥ 85% |
| Duplicated lines | ≤ 3% |
| Maintainability / Reliability / Security rating | A |
| Blocker or Critical issues | 0 |

Add the Sonar Maven plugin to `pom.xml` using the snippet at `scripts/sonar-plugin-snippet.xml`.

### GitHub Secrets — Full List

| Secret | Purpose |
|--------|---------|
| `KUBE_CONFIG` | Base64-encoded kubeconfig for kubectl deploy |
| `SONAR_TOKEN` | SonarCloud personal access token |
| `SONAR_PROJECT_KEY` | SonarCloud project key |
| `SONAR_ORG` | SonarCloud organisation slug |

---

## AWS ECS Deployment (Alternative to Kubernetes)

An AWS ECS Fargate deployment path is also provided for teams that prefer managed infrastructure. See `terraform/` for the full Terraform configuration (VPC, ECR, ECS, ALB, ElastiCache Redis, IAM).

Additional secrets required for ECS path:

| Secret | Purpose |
|--------|---------|
| `AWS_ACCESS_KEY_ID` | IAM user `github-actions-deploy` |
| `AWS_SECRET_ACCESS_KEY` | IAM user `github-actions-deploy` |

The ECS deploy workflow is in `.github/workflows/ci-cd.yml` and deploys to AWS `ap-southeast-1` (Singapore).

---

## Contributing

See [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md) for branch strategy and commit message conventions.
