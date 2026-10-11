package com.wiki.mark.client;

import java.util.Map;

/**
 * Outcome of {@link ConfluenceClient#ensureAttachment}: the remote attachment info plus whether
 * anything was uploaded ({@code false} when identical bytes were already attached).
 */
public record EnsureResult(Map<String, Object> info, boolean changed) {}
