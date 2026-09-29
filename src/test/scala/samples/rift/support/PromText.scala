package samples.rift.support

/** A parsed Prometheus text exposition: one value per series, keyed by exact metric name and label
  * set (#192). Exact-name keys are the point — the #1123 lesson: a Prometheus-client sibling such
  * as `rift_requests_total_created` must never be summed into `rift_requests_total`.
  */
final case class PromText(series: Map[PromText.Series, Double]):
  def get(s: PromText.Series): Option[Double] = series.get(s)

object PromText:

  /** A metric name plus its full label set. Label order is irrelevant, so it is a `Map`. */
  final case class Series(name: String, labels: Map[String, String]):
    override def toString: String =
      labels.toList.sorted.map((k, v) => s"$k=\"$v\"").mkString(s"$name{", ",", "}")

  /** Parse exposition text. Comment and blank lines are skipped; a sample line that does not parse
    * is an error, never silently dropped — a dropped series would read as "absent" and could turn a
    * presence assertion into a false failure (or a delta into a false zero).
    */
  def parse(text: String): Either[String, PromText] =
    text.linesIterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .foldLeft[Either[String, Map[Series, Double]]](Right(Map.empty)) { (acc, line) =>
        acc.flatMap(m => sample(line).map(m + _))
      }
      .map(PromText(_))

  private val SampleLine = """([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)(\s+\S+)?""".r
  private val Label = """([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"""".r

  private def sample(line: String): Either[String, (Series, Double)] =
    line match
      case SampleLine(name, _, labels, value, _) =>
        val ls = Option(labels).fold(Map.empty[String, String])(l =>
          Label.findAllMatchIn(l).map(m => m.group(1) -> m.group(2)).toMap
        )
        value.toDoubleOption
          .orElse(value match
            case "+Inf" => Some(Double.PositiveInfinity)
            case "-Inf" => Some(Double.NegativeInfinity)
            case "NaN" => Some(Double.NaN)
            case _ => None
          )
          .toRight(s"unparseable sample value in: $line")
          .map(v => Series(name, ls) -> v)
      case _ => Left(s"unparseable sample line: $line")

  /** A feature-file selector, `name{k=v,k2=v2}` with UNQUOTED values (a quoted value cannot sit
    * inside a Gherkin string argument), or a bare `name`.
    */
  def selector(s: String): Either[String, Series] =
    val t = s.trim
    t.indexOf('{') match
      case -1 => Right(Series(t, Map.empty))
      case i if t.endsWith("}") =>
        val body = t.substring(i + 1, t.length - 1)
        val pairs = body.split(',').map(_.trim).filter(_.nonEmpty).toList
        pairs
          .foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) { (acc, kv) =>
            acc.flatMap(m =>
              kv.split("=", 2) match
                case Array(k, v) if k.nonEmpty => Right(m + (k.trim -> v.trim))
                case _ => Left(s"bad label '$kv' in selector '$s'")
            )
          }
          .map(Series(t.substring(0, i), _))
      case _ => Left(s"selector '$s' has an unclosed '{'")
