package funnel
package agent

import scalaz.concurrent.Task
import java.util.concurrent.ConcurrentHashMap
import scalaz.\/

object RemoteInstruments {
  import collection.JavaConverters._
  import scala.reflect.runtime.universe._
  import scala.concurrent.duration._

  type Name = String

  private val counters     = new ConcurrentHashMap[Name, Counter[Periodic[Double]]]
  private val timers       = new ConcurrentHashMap[Name, Timer[Periodic[Stats]]]
  private val stringGauges = new ConcurrentHashMap[Name, Gauge[Continuous[String],String]]
  private val doubleGauges = new ConcurrentHashMap[Name, Gauge[Periodic[Stats],Double]]

  def keys: Set[Name] =
    counters.keySet.asScala.toSet ++
    timers.keySet.asScala.toSet ++
    stringGauges.keySet.asScala.toSet ++
    doubleGauges.keySet.asScala.toSet

  def metricsFromRequest(r: InstrumentRequest)(I: Instruments): Task[Unit] = {
    for {
      _ <- Task.gatherUnordered(r.counters.map(counter(_)(I)))
      _ <- Task.gatherUnordered(r.timers.map(timer(_)(I)))
      _ <- Task.gatherUnordered(r.stringGauges.map(gaugeString(_)(I)))
      _ <- Task.gatherUnordered(r.doubleGauges.map(gaugeDouble(_)(I)))
    } yield ()
  }

  private[agent] def lookup[A](key: Name)(hash: ConcurrentHashMap[Name, A]): Option[A] =
    Option(hash.get(key))

  private[agent] def gaugeString(m: ArbitraryMetric)(I: Instruments): Task[Unit] = {
    val gauge = lookup[Gauge[Continuous[String],String]](m.name)(stringGauges).getOrElse {
      val g = I.gauge[String](m.name, "")
      stringGauges.putIfAbsent(m.name, g)
      g
    }

    m.value.map(v => Task.now(gauge.set(v))
      ).getOrElse(Task.now(()))
  }

  private[agent] def gaugeDouble(m: ArbitraryMetric)(I: Instruments): Task[Unit] = {
    val gauge = lookup[Gauge[Periodic[Stats],Double]](m.name)(doubleGauges).getOrElse {
      val g = I.numericGauge(m.name, 0d)
      doubleGauges.putIfAbsent(m.name, g)
      g
    }

    m.value.map(v => Task.now(gauge.set(v.toDouble))
      ).getOrElse(Task.now(()))
  }

  private[agent] def counter(m: ArbitraryMetric)(I: Instruments): Task[Unit] = {
    val counter = lookup[Counter[Periodic[Double]]](m.name)(counters).getOrElse {
      val c = I.counter(m.name)
      counters.putIfAbsent(m.name, c)
      c
    }

    Task.now(counter.increment)
  }

  private[agent] def timer(m: ArbitraryMetric)(I: Instruments): Task[Unit] = {
    val timer = lookup[Timer[Periodic[Stats]]](m.name)(timers).getOrElse {
      val t = I.timer(m.name)
      timers.putIfAbsent(m.name, t)
      t
    }

    for {
      d <- Task.now(Duration(m.value.get))
      _ <- Task.now(timer.record(d))
    } yield ()
  }
}