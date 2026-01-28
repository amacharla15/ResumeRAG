package com.acode.resume.api;

import java.util.List;

public class ChatResponse {
    public boolean canAnswer;
    public String answer;
    public List<Citation> citations;
    public List<String> usedFields;

    public ChatResponse(boolean canAnswer, String answer, List<Citation> citations, List<String> usedFields) {
        this.canAnswer = canAnswer;
        this.answer = answer;
        this.citations = citations;
        this.usedFields = usedFields;
    }
}
