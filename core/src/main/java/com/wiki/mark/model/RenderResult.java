package com.wiki.mark.model;

import java.util.List;

/**
 * Result of rendering one Markdown document: storage XHTML plus the local refs (as written,
 * first-seen order, deduplicated) that must be uploaded as attachments.
 *
 * <p>Port of {@code mark.converter.RenderResult}.
 */
public record RenderResult(String storage, List<String> attachments) {}
