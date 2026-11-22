package org.fpcbazaar.api.files;

/**
 * An uploaded document saved on the server disk.
 * {@code path} is relative to STORAGE_DIR and never comes from user input.
 */
public record StoredFile(String originalName, String path, String contentType, long size, String sha256) {}
