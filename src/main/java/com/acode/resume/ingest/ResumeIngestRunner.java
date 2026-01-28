package com.acode.resume.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.boot.CommandLineRunner;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ResumeIngestRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ingest:false}")
    private boolean ingest;

    public ResumeIngestRunner(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!ingest) {
            System.out.println("[INGEST] app.ingest=false -> skipping ingest");
            return;
        }

        String profileJson = readClasspathText("resume/profile.json");
        String resumeText = readClasspathText("resume/resume.txt");

        List<ResumeChunker.Chunk> chunks = ResumeChunker.split(resumeText);

        transactionTemplate.execute(status -> {
            jdbcTemplate.execute("TRUNCATE TABLE resume_profile RESTART IDENTITY");
            jdbcTemplate.execute("TRUNCATE TABLE resume_chunks RESTART IDENTITY");

            jdbcTemplate.update("INSERT INTO resume_profile (profile_json) VALUES (?::jsonb)", profileJson);

            int inserted = 0;
            for (int i = 0; i < chunks.size(); i++) {
                ResumeChunker.Chunk c = chunks.get(i);

                String metadataJson = "{\"source\":\"resume.txt\",\"type\":\"" + c.type + "\"}";

                jdbcTemplate.update(
                        "INSERT INTO resume_chunks (section, content, metadata) VALUES (?, ?, ?::jsonb)",
                        c.section,
                        c.content,
                        metadataJson
                );
                inserted++;
            }

            System.out.println("[INGEST] Inserted profile=1, chunks=" + inserted);
            return null;
        });
    }

    private String readClasspathText(String path) throws Exception {
        ClassPathResource r = new ClassPathResource(path);
        byte[] bytes = r.getInputStream().readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
