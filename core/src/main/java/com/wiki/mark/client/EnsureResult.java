package com.wiki.mark.client;

import java.util.Map;

/**
 * Outcome of {@link ConfluenceClient#ensureAttachment}: the remote attachment info plus whether
 * anything was uploaded ({@code false} when identical bytes were already attached).
 *
 * @param info the remote attachment info
 * @param changed whether anything was uploaded
 */
public record EnsureResult(Map<String, Object> info, boolean changed) {}
