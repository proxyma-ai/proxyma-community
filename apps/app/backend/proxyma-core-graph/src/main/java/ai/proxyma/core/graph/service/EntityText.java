package ai.proxyma.core.graph.service;

import java.util.UUID;

/** An entity as its name vector is made from: its normalized name and its type. */
public record EntityText(UUID id, String text) {}
