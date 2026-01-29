package com.acode.resume.api;

public class RetrievalHit {
    public long chunkId;
    public String section;
    public String method;   // "fts" or "trgm"
    public double score;
    public String type;     // "bullet", "header", "line"
    public String snippet;

    public RetrievalHit(long chunkId, String section, String method, double score, String type, String snippet) {
        this.chunkId = chunkId;
        this.section = section;
        this.method = method;
        this.score = score;
        this.type = type;
        this.snippet = snippet;
    }
}
