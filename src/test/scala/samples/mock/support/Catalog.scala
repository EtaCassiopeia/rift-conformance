package samples.mock.support

import zio.bdd.mock.*
import zio.bdd.mock.dsl.*

/** Named mock sources deployed by scenario-tier `@mock(name)` fixtures
  * ([[zio.bdd.mock.fixtures.MockFixtures]]). A scenario tagged `@mock(greeting)` gets the
  * `greeting` space provisioned fresh and torn down in its fixture scope.
  */
object Catalog:
  val entries: Map[String, MockSource] = Map(
    "greeting" -> mock(get("/hello").respondWith(ok.text("hi"))).source,
    "farewell" -> mock(get("/bye").respondWith(ok.text("bye"))).source
  )
