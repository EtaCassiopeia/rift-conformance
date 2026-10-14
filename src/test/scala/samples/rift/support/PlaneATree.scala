package samples.rift.support

import java.nio.file.{Path, Paths}

/** The root of the Plane A specification on disk (#236): `features/rift/`, `rift-fixtures/`,
  * `rift-lint-fixtures/`, `rift-scripts/`. The build puts the same root on the test classpath and
  * passes it as `rift.planeATree`; specs that need a real directory (a `--scripts-dir`, `rift-lint`
  * input files) resolve it here, so they read the same tree the classpath does.
  *
  * `RIFT_FEATURES_DIR` points it at the engine repo's copy; unset, it is the in-repo
  * `src/test/plane-a`.
  */
object PlaneATree:

  val root: Path =
    sys.props
      .get("rift.planeATree")
      .orElse(sys.env.get("RIFT_FEATURES_DIR").filter(_.nonEmpty))
      .map(Paths.get(_))
      .getOrElse(Paths.get("src/test/plane-a"))
      .toAbsolutePath

  def resolve(relative: String): Path = root.resolve(relative)
