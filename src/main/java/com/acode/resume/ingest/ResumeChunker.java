package com.acode.resume.ingest;

import java.util.ArrayList;
import java.util.List;

public class ResumeChunker {

    public static class Chunk {
        public final String section;
        public final String content;
        public final String type;

        public Chunk(String section, String content, String type) {
            this.section = section;
            this.content = content;
            this.type = type;
        }
    }

    public static List<Chunk> split(String text) {
        List<Chunk> out = new ArrayList<Chunk>();

        String[] lines = text.split("\\r?\\n");
        String section = "GENERAL";

        String ctx1 = "";
        String ctx2 = "";

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            if (raw == null) continue;

            String line = raw.trim();

            boolean isContextSection = section.equals("EXPERIENCE") || section.equals("PROJECTS");

            // IMPORTANT: blank line is a boundary between entries inside EXPERIENCE/PROJECTS
            if (line.length() == 0) {
                if (isContextSection) {
                    ctx1 = "";
                    ctx2 = "";
                }
                continue;
            }

            if (isHeading(line)) {
                section = normalizeHeading(line);
                ctx1 = "";
                ctx2 = "";
                continue;
            }

            isContextSection = section.equals("EXPERIENCE") || section.equals("PROJECTS");

            if (startsWithBullet(line)) {
                String cleaned = stripBullet(line).trim();
                if (cleaned.length() > 0) {
                    if (isContextSection && (ctx1.length() > 0 || ctx2.length() > 0)) {
                        String prefix = ctx1;
                        if (ctx2.length() > 0) prefix = prefix + " | " + ctx2;
                        out.add(new Chunk(section, prefix + " - " + cleaned, "bullet"));
                    } else {
                        out.add(new Chunk(section, cleaned, "bullet"));
                    }
                }
                continue;
            }

            // non-bullet line
            if (isContextSection) {
                // Treat any non-bullet line as the start of a new context header.
                // This prevents context bleeding across jobs/projects.
                ctx1 = line;
                ctx2 = "";
                out.add(new Chunk(section, line, "header"));
                continue;
            }

            out.add(new Chunk(section, line, "line"));
        }

        return out;
    }

    private static boolean startsWithBullet(String s) {
        return s.startsWith("•") || s.startsWith("-") || s.startsWith("*");
    }

    private static String stripBullet(String s) {
        if (s.startsWith("•")) return s.substring(1);
        if (s.startsWith("-")) return s.substring(1);
        if (s.startsWith("*")) return s.substring(1);
        return s;
    }

    private static boolean isHeading(String s) {
        String u = s.toUpperCase();
        if (u.equals("EDUCATION")) return true;
        if (u.equals("SKILLS")) return true;
        if (u.equals("EXPERIENCE")) return true;
        if (u.equals("PROJECTS")) return true;
        if (u.equals("CERTIFICATIONS")) return true;

        if (s.endsWith(":") && s.length() <= 40) return true;

        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (!Character.isUpperCase(c)) return false;
            }
        }
        return hasLetter && s.length() <= 40;
    }

    private static String normalizeHeading(String s) {
        String x = s.trim();
        if (x.endsWith(":")) x = x.substring(0, x.length() - 1);
        return x.toUpperCase();
    }
}
