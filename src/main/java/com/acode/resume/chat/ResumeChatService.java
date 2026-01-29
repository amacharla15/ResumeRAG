package com.acode.resume.chat;

import com.acode.resume.api.Citation;
import com.acode.resume.api.RetrievalHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        public final List<RetrievalHit> debugHits;

        public Result(boolean canAnswer, String answer, List<Citation> citations, List<String> usedFields, List<RetrievalHit> debugHits) {
            this.canAnswer = canAnswer;
            this.answer = answer;
            this.citations = citations;
            this.usedFields = usedFields;
            this.debugHits = debugHits;
        }
    }

    public Result answer(String message) throws Exception {
        return answer(message, false);
    }

    public Result answer(String message, boolean debug) throws Exception {
        String q = message == null ? "" : message.trim();
        if (q.length() == 0) {
            return new Result(false, "Ask a question about the resume.", new ArrayList<>(), new ArrayList<>(), debug ? new ArrayList<>() : null);
        }

        FactMatch fm = matchFact(q);
        if (fm.matched) {
            Result r = answerFromProfileJson(fm);
            if (!debug) return r;
            return new Result(r.canAnswer, r.answer, r.citations, r.usedFields, new ArrayList<>());
        }

        List<Row> rows = searchChunks(q, 10);

        if (rows.size() == 0) {
            String q2 = expandQuery(q);
            if (q2.length() > 0) rows = searchChunks(q2, 10);
        }

        if (rows.size() == 0) {
            return new Result(false, "I don’t have that information in my resume.", new ArrayList<>(), new ArrayList<>(), debug ? new ArrayList<>() : null);
        }

        List<RetrievalHit> dbg = null;
        if (debug) {
            dbg = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                dbg.add(new RetrievalHit(r.id, r.section, r.method, r.score, r.type, clip(r.content, 160)));
            }
        }

        List<Row> answerRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!"header".equals(r.type)) {
                answerRows.add(r);
            }
        }
        if (answerRows.size() == 0) answerRows = rows;

        AnswerPack pack = formatGroupedAnswer(answerRows);
        return new Result(true, pack.answer, pack.citations, new ArrayList<>(), dbg);
    }

    private String expandQuery(String q) {
        String s = q.toLowerCase();

        if (s.contains("csu") && s.contains("chico")) return "California State University Chico Web Developer";
        if (s.contains("chico state")) return "California State University Chico";
        if (s.contains("csuchico")) return "California State University Chico";

        return "";
    }

    private static class AnswerPack {
        public final String answer;
        public final List<Citation> citations;

        public AnswerPack(String answer, List<Citation> citations) {
            this.answer = answer;
            this.citations = citations;
        }
    }

    private AnswerPack formatGroupedAnswer(List<Row> rows) {
        Map<String, List<Row>> groups = new LinkedHashMap<>();

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);

            String prefix = extractPrefix(r.content);
            String key;
            if (prefix.length() > 0) key = prefix;
            else key = r.section;

            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(r);
        }

        StringBuilder sb = new StringBuilder();
        List<Citation> cites = new ArrayList<>();

        for (Map.Entry<String, List<Row>> e : groups.entrySet()) {
            String groupTitle = e.getKey();
            List<Row> groupRows = e.getValue();

            if (!groupTitle.equals("EXPERIENCE") && !groupTitle.equals("PROJECTS") && !groupTitle.equals("SKILLS") && !groupTitle.equals("EDUCATION")) {
                sb.append(groupTitle).append(":\n");
            }

            for (int i = 0; i < groupRows.size(); i++) {
                Row r = groupRows.get(i);

                String bulletText = extractAfterDash(r.content);
                if (bulletText.length() == 0) bulletText = r.content;

                String snip = clip(bulletText, 260);
                sb.append("- ").append(snip).append("\n");

                cites.add(new Citation(r.id, r.section, snip));
            }

            sb.append("\n");
        }

        return new AnswerPack(sb.toString().trim(), cites);
    }

    private String extractPrefix(String content) {
        if (content == null) return "";
        int idx = content.indexOf(" - ");
        if (idx <= 0) return "";
        return content.substring(0, idx).trim();
    }

    private String extractAfterDash(String content) {
        if (content == null) return "";
        int idx = content.indexOf(" - ");
        if (idx < 0) return "";
        return content.substring(idx + 3).trim();
    }

    // ---------------- Fact handling ----------------

    private static class FactMatch {
        public boolean matched;
        public String fieldPath;
        public String label;

        public FactMatch(boolean matched, String fieldPath, String label) {
            this.matched = matched;
            this.fieldPath = fieldPath;
            this.label = label;
        }
    }

    private FactMatch matchFact(String q) {
        String s = q.toLowerCase();

        if (containsAny(s, "who are you", "who is this resume", "your name", "name?")) return new FactMatch(true, "name", "name");
        if (containsAny(s, "email", "email id", "mail id")) return new FactMatch(true, "email", "email");
        if (containsAny(s, "phone number", "contact number", "your phone")) return new FactMatch(true, "phone", "phone");
        if (containsAny(s, "where are you based", "based in", "your location", "location")) return new FactMatch(true, "location", "location");

        if (containsAny(s, "gpa")) return new FactMatch(true, "education[0].gpa", "education.gpa");
        if (containsAny(s, "graduation", "expected may", "may 2027")) return new FactMatch(true, "education[0].grad", "education.grad");

        if (containsAny(s, "skills", "skill set", "skillset")) return new FactMatch(true, "skills_all", "skills");

        if (containsAny(s, "languages")) return new FactMatch(true, "skills.languages", "skills.languages");
        if (containsAny(s, "frameworks", "libraries")) return new FactMatch(true, "skills.frameworks", "skills.frameworks");
        if (containsAny(s, "databases", "cloud", "devops", "infra")) return new FactMatch(true, "skills.infra", "skills.infra");

        if (containsAny(s, "certification", "certifications")) return new FactMatch(true, "certifications", "certifications");

        return new FactMatch(false, "", "");
    }

    private Result answerFromProfileJson(FactMatch fm) throws Exception {
        String json = jdbcTemplate.queryForObject(
                "SELECT profile_json::text FROM resume_profile ORDER BY id DESC LIMIT 1",
                String.class
        );
        JsonNode root = objectMapper.readTree(json);

        if (fm.fieldPath.equals("skills_all")) {
            JsonNode skills = root.get("skills");
            if (skills == null || skills.isNull()) {
                return new Result(false, "I don’t have that information in my resume.", new ArrayList<>(), List.of(fm.label), null);
            }

            StringBuilder sb = new StringBuilder();

            appendArray(sb, "Languages", skills.get("languages"));
            appendArray(sb, "Frameworks/Libraries", skills.get("frameworks"));
            appendArray(sb, "Data/Cloud/DevOps", skills.get("infra"));
            appendArray(sb, "Concepts/Testing", skills.get("concepts"));

            String ans = sb.toString().trim();
            if (ans.length() == 0) ans = "I don’t have that information in my resume.";
            return new Result(true, ans, new ArrayList<>(), List.of("skills"), null);
        }

        JsonNode value = getByPath(root, fm.fieldPath);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return new Result(false, "I don’t have that information in my resume.", new ArrayList<>(), List.of(fm.label), null);
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

        return new Result(true, answer, new ArrayList<>(), List.of(fm.label), null);
    }

    private void appendArray(StringBuilder sb, String title, JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.size() == 0) return;
        sb.append(title).append(":\n");
        for (int i = 0; i < arr.size(); i++) {
            sb.append("- ").append(arr.get(i).asText()).append("\n");
        }
        sb.append("\n");
    }

    private JsonNode getByPath(JsonNode root, String path) {
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
        public final String method;
        public final String type;

        public Row(long id, String section, String content, double score, String method, String type) {
            this.id = id;
            this.section = section;
            this.content = content;
            this.score = score;
            this.method = method;
            this.type = type;
        }
    }

    private List<Row> searchChunks(String q, int limit) {
        String sql1 =
                "SELECT id, section, content, " +
                        "ts_rank(tsv, plainto_tsquery('english', ?)) AS score, " +
                        "COALESCE(metadata->>'type','') AS type " +
                        "FROM resume_chunks " +
                        "WHERE tsv @@ plainto_tsquery('english', ?) " +
                        "ORDER BY score DESC " +
                        "LIMIT ?";

        List<Row> a = jdbcTemplate.query(
                sql1,
                (rs, rowNum) -> new Row(
                        rs.getLong("id"),
                        rs.getString("section"),
                        rs.getString("content"),
                        rs.getDouble("score"),
                        "fts",
                        rs.getString("type")
                ),
                q, q, limit
        );

        if (a.size() > 0 && a.get(0).score >= 0.03) {
            return a;
        }

        String sql2 =
                "SELECT id, section, content, " +
                        "similarity(content, ?) AS score, " +
                        "COALESCE(metadata->>'type','') AS type " +
                        "FROM resume_chunks " +
                        "WHERE content % ? " +
                        "ORDER BY score DESC " +
                        "LIMIT ?";

        List<Row> b = jdbcTemplate.query(
                sql2,
                (rs, rowNum) -> new Row(
                        rs.getLong("id"),
                        rs.getString("section"),
                        rs.getString("content"),
                        rs.getDouble("score"),
                        "trgm",
                        rs.getString("type")
                ),
                q, q, limit
        );

        List<Row> out = new ArrayList<>();
        for (int i = 0; i < b.size(); i++) {
            if (b.get(i).score >= 0.12) out.add(b.get(i));
        }
        if (out.size() > 0) return out;

        if (a.size() > 0 && a.get(0).score >= 0.015) return a;

        return new ArrayList<>();
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        String x = s.trim();
        if (x.length() <= max) return x;
        return x.substring(0, max).trim() + "...";
    }
}
