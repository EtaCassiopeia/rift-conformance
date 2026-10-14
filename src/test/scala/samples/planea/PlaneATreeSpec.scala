package samples.planea

import zio.*
import zio.test.*

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** The Plane A suites load their feature files from the classpath (#236). zio-bdd resolves a
  * `classpath:` feature that is not there to no files at all, so a tree missing a feature would run
  * zero scenarios and pass, and two trees on the classpath at once would run every feature twice.
  * This spec fails in both cases, for whichever tree is active (`RIFT_FEATURES_DIR` or the in-repo
  * `src/test/plane-a`). It spawns no engine, so it runs in the `lintFeatures` gate.
  */
object PlaneATreeSpec extends ZIOSpecDefault:

  private val FeatureRef = """featureDirs = Array\("classpath:([^"]+)"\)""".r

  /** Every `classpath:` feature a suite under `samples` names. */
  private def referencedFeatures: List[String] =
    Using.resource(Files.walk(Paths.get("src/test/scala/samples"))) { paths =>
      paths.iterator.asScala
        .filter(_.toString.endsWith(".scala"))
        .flatMap(p => FeatureRef.findAllMatchIn(Files.readString(p)).map(_.group(1)))
        .toList
        .sorted
    }

  private def copiesOnClasspath(resource: String): Int =
    getClass.getClassLoader.getResources(resource).asScala.size

  def spec = suite("Plane A tree (#236)")(
    test("every feature a suite names is on the classpath exactly once") {
      val features = referencedFeatures
      val wrong = features.map(f => f -> copiesOnClasspath(f)).filter(_._2 != 1)
      assertTrue(features.size >= 23, wrong.isEmpty)
    },
    test("the fixture roots come from one tree") {
      val wrong = List("rift-fixtures", "rift-lint-fixtures", "rift-scripts")
        .map(r => r -> copiesOnClasspath(r))
        .filter(_._2 != 1)
      assertTrue(wrong.isEmpty)
    }
  )
