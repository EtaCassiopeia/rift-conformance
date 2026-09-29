package io.github.achirdlabs.rift.embedded;

import io.github.achirdlabs.rift.EmbeddedOptions;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
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
 * <p>{@code RiftFfi} (rift-java 0.3.3) binds neither {@code rift_abi_version} nor {@code
 * rift_stop_intercept}, so those two are bound here directly against the same library.
 *
 * <p>Compiled only on a JDK 22+ build (the FFM floor of rift-java-embedded).
 */
public final class ConformanceFfi implements EmbeddedFfi {

  private final Arena libArena;
  private final RiftFfi ffi;
  private final MemorySegment handle;
  private final MethodHandle abiVersion;
  private final MethodHandle stopIntercept;

  public ConformanceFfi() {
    var lib = NativeLibraryResolver.resolve(EmbeddedOptions.builder().build()).path();
    var arena = Arena.ofShared();
    try {
      var bound = RiftFfi.load(lib, arena);
      var symbols = SymbolLookup.libraryLookup(lib, arena);
      var linker = Linker.nativeLinker();
      this.abiVersion =
          linker.downcallHandle(
              symbols.find("rift_abi_version").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
      this.stopIntercept =
          linker.downcallHandle(
              symbols.find("rift_stop_intercept").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
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
  public String verify(int port, String bodyJson) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.verify(handle, port, FfmCompat.allocateCString(a, bodyJson)));
    }
  }

  @Override
  public int abiVersion() {
    try {
      return (int) abiVersion.invokeExact();
    } catch (Throwable t) {
      throw new IllegalStateException("rift_abi_version failed", t);
    }
  }

  @Override
  public void stopIntercept() {
    int status;
    try {
      status = (int) stopIntercept.invokeExact(handle);
    } catch (Throwable t) {
      throw new IllegalStateException("rift_stop_intercept failed", t);
    }
    check(status);
  }

  @Override
  public String interceptListRules() {
    return readAndFree(ffi.interceptListRules(handle));
  }

  @Override
  public String applyConfig(String json) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.applyConfig(handle, FfmCompat.allocateCString(a, json)));
    }
  }

  @Override
  public String flowStateGet(int port, String flowId, String key) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(
          ffi.flowStateGet(
              handle, port, FfmCompat.allocateCString(a, flowId), FfmCompat.allocateCString(a, key)));
    }
  }

  @Override
  public void flowStatePut(int port, String flowId, String key, String valueJson) {
    try (var a = Arena.ofConfined()) {
      check(
          ffi.flowStatePut(
              handle,
              port,
              FfmCompat.allocateCString(a, flowId),
              FfmCompat.allocateCString(a, key),
              FfmCompat.allocateCString(a, valueJson)));
    }
  }

  @Override
  public void flowStateDelete(int port, String flowId, String key) {
    try (var a = Arena.ofConfined()) {
      check(
          ffi.flowStateDelete(
              handle, port, FfmCompat.allocateCString(a, flowId), FfmCompat.allocateCString(a, key)));
    }
  }

  @Override
  public void spaceAddStub(int port, String flowId, String stubJson) {
    try (var a = Arena.ofConfined()) {
      check(
          ffi.spaceAddStub(
              handle,
              port,
              FfmCompat.allocateCString(a, flowId),
              FfmCompat.allocateCString(a, stubJson)));
    }
  }

  @Override
  public String spaceListStubs(int port, String flowId) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.spaceListStubs(handle, port, FfmCompat.allocateCString(a, flowId)));
    }
  }

  @Override
  public void spaceDelete(int port, String flowId) {
    try (var a = Arena.ofConfined()) {
      check(ffi.spaceDelete(handle, port, FfmCompat.allocateCString(a, flowId)));
    }
  }

  @Override
  public String spaceRecorded(int port, String flowId) {
    try (var a = Arena.ofConfined()) {
      return readAndFree(ffi.spaceRecorded(handle, port, FfmCompat.allocateCString(a, flowId)));
    }
  }

  /**
   * Fail one call (clearing recorded requests on port 0, which is never an imposter), optionally
   * follow it with a successful one, then read {@code rift_last_error} — all on this one thread,
   * since the error is thread-local. The raw status is not routed through {@link #check}, which
   * would read the error itself.
   */
  @Override
  public String lastErrorAfterFailure(boolean thenSucceed) {
    if (ffi.clearRecorded(handle, 0) == 0) {
      throw new IllegalStateException("clearing recorded requests on port 0 unexpectedly succeeded");
    }
    if (thenSucceed) {
      readAndFree(ffi.listImposters(handle, MemorySegment.NULL));
    }
    var e = ffi.lastError();
    if (RiftFfi.isNull(e)) {
      return null;
    }
    try {
      return RiftFfi.readString(e);
    } finally {
      ffi.free(e);
    }
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
