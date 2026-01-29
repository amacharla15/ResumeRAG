package com.acode.resume.api;

import jakarta.validation.constraints.NotBlank;

public class ChatRequest {
    @NotBlank
    public String message;

    // Optional: if omitted in JSON, defaults to false
    public boolean debug = false;
}
