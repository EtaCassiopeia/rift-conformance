package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import samples.rift.support.EmbeddedFfi;

/**
 * The Plane A embedded harness's binding to the rift C-ABI (see {@code EmbeddedAdmin}).
 *
 * <p>It lives in rift-java's package because {@link RiftFfi} and {@link NativeLibraryResolver}
 * are package-private. It drives {@code RiftFfi} directly rather than {@code FfiCalls}, because
 * {@code FfiCalls} keeps the engine handle private and has no truststore export. The call
 * discipline is the same as {@code FfiCalls}: a string result is read then freed, a null result or
 * a non-zero status is an engine error carrying {@code rift_last_error}.
 *
 * <p>Compiled only on a JDK 22+ build (the FFM floor of rift-java-embedded).
 */
public final class ConformanceFfi implements EmbeddedFfi {

  private final Arena libArena;
  private final RiftFfi ffi;
  private final MemorySegment handle;

  public ConformanceFfi() {
    var lib = NativeLibraryResolver.resolve(EmbeddedOptions.builder().build()).path();
    var arena = Arena.ofShared();
    try {
      var bound = RiftFfi.load(lib, arena);
      var h = bound.start();
      if (RiftFfi.isNull(h)) {
        throw new IllegalStateException("failed to start the embedded rift engine");
      }
      this.libArena = arena;
      this.ffi = bound;
      this.handle = h;
    } catch (RuntimeException e) {
      arena.close();
      throw e;
    }
  }

  @Override
  public String serveAdmin(String optionsJson) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.serveAdmin(handle, FfmCompat.allocateCString(a, optionsJson)));
    }
  }

  @Override
  public String buildInfo() {
    // rift_build_info returns a static `const char *`: freeing it would release the engine's own
    // cached buffer, so it is read but never passed to rift_free.
    var s = ffi.buildInfo();
    if (RiftFfi.isNull(s)) {
      throw engineError();
    }
    return RiftFfi.readString(s);
  }

  @Override
  public String startIntercept(String optionsJson) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.startIntercept(handle, FfmCompat.allocateCString(a, optionsJson)));
    }
  }

  @Override
  public void interceptAddRules(String rulesJson) {
    try (var a = Arena.ofConfined()) {
      check(ffi.interceptAddRules(handle, FfmCompat.allocateCString(a, rulesJson)));
    }
  }

  @Override
  public void interceptExportTruststore(String format, String password, String outPath) {
    try (var a = Arena.ofConfined()) {
      check(
          ffi.interceptExportTruststore(
              handle,
              FfmCompat.allocateCString(a, format),
              FfmCompat.allocateCString(a, password),
              FfmCompat.allocateCString(a, outPath)));
    }
  }

  @Override
  public String listImposters(String optionsJson) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.listImposters(handle, FfmCompat.allocateCString(a, optionsJson)));
    }
  }

  @Override
  public String getImposter(int port, String optionsJson) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(
          ffi.getImposter(handle, port, FfmCompat.allocateCString(a, optionsJson)));
    }
  }

  @Override
  public void addStub(int port, String stubJson, int index) {
    try (var a = Arena.ofConfined()) {
      check(ffi.addStub(handle, port, FfmCompat.allocateCString(a, stubJson), index));
    }
  }

  @Override
  public String getStub(int port, String refJson) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.getStub(handle, port, FfmCompat.allocateCString(a, refJson)));
    }
  }

  @Override
  public void updateStub(int port, String refJson, String stubJson) {
    try (var a = Arena.ofConfined()) {
      check(
          ffi.updateStub(
              handle,
              port,
              FfmCompat.allocateCString(a, refJson),
              FfmCompat.allocateCString(a, stubJson)));
    }
  }

  @Override
  public void deleteStub(int port, String refJson) {
    try (var a = Arena.ofConfined()) {
      check(ffi.deleteStub(handle, port, FfmCompat.allocateCString(a, refJson)));
    }
  }

  @Override
  public void clearRecorded(int port) {
    check(ffi.clearRecorded(handle, port));
  }

  @Override
  public void clearProxyRecordings(int port) {
    check(ffi.clearProxyRecordings(handle, port));
  }

  @Override
  public void setImposterEnabled(int port, boolean enabled) {
    check(ffi.setImposterEnabled(handle, port, enabled ? 1 : 0));
  }

  @Override
  public String scenarios(int port, String flowId) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.scenarios(handle, port, cStringOrNull(a, flowId)));
    }
  }

  @Override
  public void setScenarioState(int port, String name, String stateJson) {
    try (var a = Arena.ofConfined()) {
      check(
          ffi.setScenarioState(
              handle,
              port,
              FfmCompat.allocateCString(a, name),
              FfmCompat.allocateCString(a, stateJson)));
    }
  }

  @Override
  public void resetScenarios(int port, String flowId) {
    try (var a = Arena.ofConfined()) {
      check(ffi.resetScenarios(handle, port, cStringOrNull(a, flowId)));
    }
  }

  @Override
  public String stubWarnings(int port) {
    return readAndFree(ffi.stubWarnings(handle, port));
  }

  @Override
  public void stop() {
    try {
      ffi.stop(handle);
    } finally {
      libArena.close();
    }
  }

  /** A null {@code flowId} crosses the C-ABI as a null pointer: the imposter's default flow. */
  private static MemorySegment cStringOrNull(Arena a, String s) {
    return s == null ? MemorySegment.NULL : FfmCompat.allocateCString(a, s);
  }

  private String readAndFree(MemorySegment s) {
    if (RiftFfi.isNull(s)) {
      throw engineError();
    }
    try {
      return RiftFfi.readString(s);
    } finally {
      ffi.free(s);
    }
  }

  private void check(int status) {
    if (status != 0) {
      throw engineError();
    }
  }

  private RuntimeException engineError() {
    var e = ffi.lastError();
    if (RiftFfi.isNull(e)) {
      return new IllegalStateException("rift engine call failed with no error message");
    }
    try {
      return new IllegalStateException("rift engine call failed: " + RiftFfi.readString(e));
    } finally {
      ffi.free(e);
    }
  }
}
