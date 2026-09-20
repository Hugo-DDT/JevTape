package io.jevtape.cassette;

/**
 * Where cassettes live. Storage only: no matching, no fingerprinting, no network (charter §43).
 */
public interface CassetteRepository {

    /**
     * @param name cassette name without directory or {@code .json} suffix
     * @throws io.jevtape.shared.CassetteNotFound when no such cassette exists
     * @throws io.jevtape.shared.CassetteCorrupted when the file is not a readable v1 document
     * @throws io.jevtape.shared.CassetteVersionUnsupported when it declares a version this build
     *         cannot read, or declares none at all
     * @throws io.jevtape.shared.ConfigurationError when the name is not a safe cassette name
     */
    Cassette read(String name);

    /**
     * Writes {@code cassette} under its own name, replacing any cassette already stored there.
     *
     * @throws io.jevtape.shared.StorageFailure when the file cannot be written
     * @throws io.jevtape.shared.ConfigurationError when the name is not a safe cassette name
     */
    void write(Cassette cassette);
}
