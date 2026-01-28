package com.acode.resume.chat;

import com.acode.resume.api.Citation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResumeChatService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResumeChatService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public static class Result {
        public final boolean canAnswer;
        public final String answer;
        public final List<Citation> citations;
        public final List<String> usedFields;

        public Result(boolean canAnswer, String answer, List<Citation> citations, List<String> usedFields) {
            this.canAnswer = canAnswer;
            this.answer = answer;
            this.citations = citations;
            this.usedFields = usedFields;
        }
    }

    public Result answer(String message) throws Exception {
        String q = message == null ? "" : message.trim();
        if (q.length() == 0) {
            return new Result(false, "Ask a question about the resume.", new ArrayList<>(), new ArrayList<>());
        }

        // 1) Fact routing (no model)
        FactMatch fm = matchFact(q);
        if (fm.matched) {
            return answerFromProfileJson(fm);
        }

        // 2) Narrative routing (search chunks + citations)
        List<Row> rows = searchChunks(q, 6);

        if (rows.size() == 0) {
            return new Result(false, "I don’t have that information in my resume.", new ArrayList<>(), new ArrayList<>());
        }

        // Build extractive answer: join top snippets (safe, no hallucination)
        StringBuilder sb = new StringBuilder();
        List<Citation> cites = new ArrayList<Citation>();

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String snip = clip(r.content, 260);
            sb.append("- ").append(snip).append("\n");
            cites.add(new Citation(r.id, r.section, snip));
        }

        String answer = sb.toString().trim();
        return new Result(true, answer, cites, new ArrayList<>());
    }

    // ---------------- Fact handling ----------------

    private static class FactMatch {
        public boolean matched;
        public String fieldPath;     // e.g. "name" or "skills.languages"
        public String label;         // for usedFields
        public FactMatch(boolean matched, String fieldPath, String label) {
            this.matched = matched;
            this.fieldPath = fieldPath;
            this.label = label;
        }
    }

    private FactMatch matchFact(String q) {
        String s = q.toLowerCase();

        if (containsAny(s, "name", "who are you", "who is this resume")) return new FactMatch(true, "name", "name");
        if (containsAny(s, "email", "mail id")) return new FactMatch(true, "email", "email");
        if (containsAny(s, "phone", "number", "contact")) return new FactMatch(true, "phone", "phone");
        if (containsAny(s, "location", "where", "based")) return new FactMatch(true, "location", "location");
        if (containsAny(s, "gpa")) return new FactMatch(true, "education[0].gpa", "education.gpa");
        if (containsAny(s, "graduation", "grad", "may 2027")) return new FactMatch(true, "education[0].grad", "education.grad");
        if (containsAny(s, "languages")) return new FactMatch(true, "skills.languages", "skills.languages");
        if (containsAny(s, "framework", "frameworks")) return new FactMatch(true, "skills.frameworks", "skills.frameworks");
        if (containsAny(s, "databases", "infra", "cloud")) return new FactMatch(true, "skills.infra", "skills.infra");
        if (containsAny(s, "certification", "certifications")) return new FactMatch(true, "certifications", "certifications");

        return new FactMatch(false, "", "");
    }

    private Result answerFromProfileJson(FactMatch fm) throws Exception {
        String json = jdbcTemplate.queryForObject("SELECT profile_json::text FROM resume_profile ORDER BY id DESC LIMIT 1", String.class);
        JsonNode root = objectMapper.readTree(json);

        JsonNode value = getByPath(root, fm.fieldPath);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return new Result(false, "I don’t have that information in my resume.", new ArrayList<>(), List.of(fm.label));
        }

        String answer;
        if (value.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < value.size(); i++) {
                sb.append("- ").append(value.get(i).asText()).append("\n");
            }
            answer = sb.toString().trim();
        } else {
            answer = value.asText();
        }

        return new Result(true, answer, new ArrayList<>(), List.of(fm.label));
    }

    private JsonNode getByPath(JsonNode root, String path) {
        // supports: a.b , a[0].b
        String[] parts = path.split("\\.");
        JsonNode cur = root;

        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];

            int idxStart = p.indexOf('[');
            if (idxStart >= 0) {
                String name = p.substring(0, idxStart);
                int idxEnd = p.indexOf(']');
                int idx = Integer.parseInt(p.substring(idxStart + 1, idxEnd));

                cur = cur.get(name);
                if (cur == null || !cur.isArray() || idx < 0 || idx >= cur.size()) return null;
                cur = cur.get(idx);
            } else {
                cur = cur.get(p);
            }

            if (cur == null) return null;
        }
        return cur;
    }

    private boolean containsAny(String s, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            if (s.contains(keys[i])) return true;
        }
        return false;
    }

    // ---------------- Chunk search ----------------

    private static class Row {
        public final long id;
        public final String section;
        public final String content;
        public final double score;

        public Row(long id, String section, String content, double score) {
            this.id = id;
            this.section = section;
            this.content = content;
            this.score = score;
        }
    }

    private List<Row> searchChunks(String q, int limit) {
        // 1) Full-text search
        String sql1 =
                "SELECT id, section, content, ts_rank(tsv, plainto_tsquery('english', ?)) AS score " +
                        "FROM resume_chunks " +
                        "WHERE tsv @@ plainto_tsquery('english', ?) " +
                        "ORDER BY score DESC " +
                        "LIMIT ?";

        List<Row> a = jdbcTemplate.query(
                sql1,
                (rs, rowNum) -> new Row(rs.getLong("id"), rs.getString("section"), rs.getString("content"), rs.getDouble("score")),
                q, q, limit
        );

        if (a.size() >= 2) return a;

        // 2) Fuzzy fallback (trigram similarity)
        String sql2 =
                "SELECT id, section, content, similarity(content, ?) AS score " +
                        "FROM resume_chunks " +
                        "WHERE content % ? " +
                        "ORDER BY score DESC " +
                        "LIMIT ?";

        List<Row> b = jdbcTemplate.query(
                sql2,
                (rs, rowNum) -> new Row(rs.getLong("id"), rs.getString("section"), rs.getString("content"), rs.getDouble("score")),
                q, q, limit
        );

        // Hard gate: if even fuzzy scores are weak, refuse
        List<Row> out = new ArrayList<Row>();
        for (int i = 0; i < b.size(); i++) {
            if (b.get(i).score >= 0.15) { // simple threshold; adjust later
                out.add(b.get(i));
            }
        }
        return out;
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        String x = s.trim();
        if (x.length() <= max) return x;
        return x.substring(0, max).trim() + "...";
    }
}
