# ResumeRAG

A Spring Boot (Java 21) + Postgres project that turns a resume into a **retrieval-backed Q&A service**.  
Instead of “guessing,” the API first **retrieves the most relevant resume chunks** using Postgres search, then returns an answer **grounded in that evidence** with citations.

## What I built
- **Resume ingestion pipeline**: loads `resume.txt` + `profile.json`, chunks the resume by section (EXPERIENCE / PROJECTS / SKILLS), and stores it in Postgres.
- **Context-aware chunking**: bullets are prefixed with their parent headings (role/company/section context) so questions like *“What did you do at Cognizant?”* retrieve the correct experience chunks.
- **Fast retrieval in Postgres**:
  - Full-text search using `tsvector` + **GIN** indexes
  - Fuzzy matching using **pg_trgm** (trigram) for partial/approximate queries
- **Chat API**: `POST /api/chat` returns:
  - `canAnswer` (based on retrieval confidence)
  - `answer`
  - `citations` (the supporting chunk ids/snippets used)

## Tech Stack
- Java 21, Spring Boot 3
- PostgreSQL 16
- Flyway migrations
- Docker Compose for local Postgres

## How to run locally
1) Start Postgres:
```bash
docker compose up -d
Configure DB in src/main/resources/application.yml

Ingest once (loads src/main/resources/resume/*):

set app.ingest=true

run the app once

set app.ingest=false for normal runs

Start the service:

./mvnw spring-boot:run
API
POST /api/chat

{ "message": "What did you do at Cognizant?" }
Response includes answer + evidence citations.

Key files
ingest/ResumeIngestRunner.java — one-time ingest runner

ingest/ResumeChunker.java — section + context-aware chunking

chat/ResumeChatService.java — retrieval + response assembly

db/migration/V1__init.sql — schema + indexes (FTS + trigram)
