package io.jevtape.shared;

import static java.lang.reflect.Modifier.isAbstract;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 锁定 charter §71 的错误分类体系:这十种类型,全部归属于同一个非受检异常根类。 */
class ErrorModelTest {

    private static final List<Class<? extends JevTapeException>> ERROR_TYPES = List.of(
            CassetteNotFound.class,
            CassetteCorrupted.class,
            CassetteVersionUnsupported.class,
            ReplayMiss.class,
            ContractMismatch.class,
            UpstreamUnavailable.class,
            UpstreamTimeout.class,
            InvalidJevRequest.class,
            StorageFailure.class,
            ConfigurationError.class);

    @Test
    void everyErrorTypeIsAFinalUncheckedSubtypeOfTheRoot() {
        assertThat(ERROR_TYPES).hasSize(10);
        assertThat(isAbstract(JevTapeException.class.getModifiers())).isTrue();
        assertThat(JevTapeException.class).isAssignableTo(RuntimeException.class);
        assertThat(ERROR_TYPES).allSatisfy(type -> {
            assertThat(type).isAssignableTo(JevTapeException.class);
            assertThat(isAbstract(type.getModifiers())).isFalse();
        });
    }

    @Test
    void typesThatWrapAFailureKeepItsCause() {
        IOException io = new IOException("disk full");

        assertThat(new CassetteCorrupted("unparsable JSON", io)).hasCause(io).hasMessage("unparsable JSON");
        assertThat(new UpstreamUnavailable("connection refused", io)).hasCause(io);
        assertThat(new UpstreamTimeout("no answer in 30s", io)).hasCause(io);
        assertThat(new StorageFailure("cannot write cassette", io)).hasCause(io);
        assertThat(new ConfigurationError("bad config.json", io)).hasCause(io);
    }
}
