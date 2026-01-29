package com.acode.resume.api;

public class Citation {
    public long chunkId;
    public String section;
    public String snippet;

    public Citation(long chunkId, String section, String snippet) {
        this.chunkId = chunkId;
        this.section = section;
        this.snippet = snippet;
    }
}
