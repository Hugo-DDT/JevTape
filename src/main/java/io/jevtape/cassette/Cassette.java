package io.jevtape.cassette;

import java.util.Objects;

/**
 * A cassette document: one recorded Jev interaction plus the two fields that identify it on disk,
 * {@code schemaVersion} and {@code name} (charter §22, §25).
 *
 * <p>The component order is the field order on disk and part of the published format — reordering
 * them rewrites every committed cassette.
 */
public record Cassette(int schemaVersion,
                       String name,
                       CassetteMetadata metadata,
                       RecordedRequest request,
                       RecordedResponse response,
                       Fingerprints fingerprints) {

    /** The version this build writes and the only one it reads (charter §26). */
    public static final int SCHEMA_VERSION = 1;

    public Cassette {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(fingerprints, "fingerprints");
    }

    /** Wraps a recorded interaction into a cassette of the current schema version. */
    public static Cassette of(String name, Interaction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        return new Cassette(SCHEMA_VERSION, name, interaction.metadata(), interaction.request(),
                interaction.response(), interaction.fingerprints());
    }

    public Interaction interaction() {
        return new Interaction(metadata, request, response, fingerprints);
    }
}
