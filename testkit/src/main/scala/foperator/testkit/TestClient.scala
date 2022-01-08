package foperator.testkit

import cats.Eq
import cats.effect.concurrent.{MVar, MVar2}
import cats.effect.{Clock, Concurrent}
import cats.implicits._
import foperator.internal.{Broadcast, Logging}
import foperator.types._
import foperator._
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock

import java.time.Instant
import java.util.concurrent.TimeUnit

class TestClient[IO[_]](
  state: MVar2[IO, TestClient.State],
  val topic: Broadcast[IO, Event[TestClient.Entry]],
  val auditors: List[Event[TestClient.Entry] => IO[Unit]],
)(implicit io: Concurrent[IO]) extends Client[IO, TestClient[IO]] with Logging {

  override def apply[T]
    (implicit e: Engine[IO, TestClient[IO], T], res: ObjectResource[T])
    : Operations[IO, TestClient[IO], T]
    = new Operations[IO, TestClient[IO], T](this)

  def readState = state.read

  def all[T](implicit res: ObjectResource[T]): IO[List[T]] = readState.map { state =>
    state.toList.mapFilter {
      case (k, v) => ResourceKey.cast(k, v)
    }
  }

  def modifyState[B](f: TestClient.State => IO[(TestClient.State, B)]): IO[B] = state.modify(f)

  def modifyState_(f: TestClient.State => IO[TestClient.State]): IO[Unit] = modifyState(s => f(s).map(r => (r, ())))

  private [foperator] def publish(update: Event[TestClient.Entry]) = {
    auditors.traverse_(audit => audit(update)) >>
      io.delay(logger.debug("publishing {}({})", Event.desc(update), update.raw._1.id)) >>
      topic.publish1(update)
  }

  def withAudit[T](audit: Event[T] => IO[Unit])(implicit res: ObjectResource[T]): TestClient[IO] = {
    val auditor: Event[TestClient.Entry] => IO[Unit] = { entry =>
      ResourceKey.castEvent(entry).fold(io.unit)(audit)
    }
    new TestClient(state, topic, auditor :: auditors)
  }
}

object TestClient extends Client.Companion[Task, TestClient[Task]] {
  // Internal state is untyped for simplicity.
  // Correct usage requires that no two types have the same `kind` + `apiVersion`
  private [testkit] type State = Map[ResourceKey, Any]
  private [testkit] type Entry = (ResourceKey, Any)

  def apply[IO[_]](implicit io: Concurrent[IO]): IO[TestClient[IO]] = {
    for {
      state <- MVar.of(Map.empty: State)
      topic <- Broadcast[IO, Event[(ResourceKey, Any)]]
    } yield new TestClient[IO](state, topic, Nil)
  }

  def unsafe(): TestClient[Task] = apply[Task].runSyncUnsafe()(Scheduler.global, implicitly[CanBlock])

  implicit def implicitEngine[IO[_], T]
    (implicit io: Concurrent[IO], clock: Clock[IO], res: ObjectResource[T], eq: Eq[T])
  : foperator.types.Engine[IO, TestClient[IO], T]
  = new TestClientEngineImpl[IO, T]

  implicit def implicitOps[IO[_], T](c: TestClient[IO])
    (implicit io: Concurrent[IO], engine: Engine[IO, TestClient[IO], T], res: ObjectResource[T])
  : Operations[IO, TestClient[IO], T]
  = new Operations(c)
}

class TestClientError(val e: ClientError) extends RuntimeException(e.throwable)

case class ResourceKind(kind: String)
object ResourceKind {
  def forClass[T](implicit res: HasKind[T]) = new ResourceKind(res.kind)
  implicit val eq: Eq[ResourceKind] = Eq.fromUniversalEquals
}
case class ResourceKey(kind: ResourceKind, id: Id[Any])
object ResourceKey {
  def id[T](id: Id[T])(implicit res: HasKind[T]) = ResourceKey(ResourceKind.forClass[T], id.asInstanceOf[Id[Any]])

  def cast[T](key: ResourceKey, v: Any)(implicit res: HasKind[T]): Option[T] = {
    if (key.kind === ResourceKind.forClass[T]) {
      Some(v.asInstanceOf[T])
    } else {
      None
    }
  }

  def castEvent[T](event: Event[TestClient.Entry])(implicit res: HasKind[T]): Option[Event[T]] = {
    event match {
      case Event.Updated((k, v)) => cast[T](k, v).map(Event.Updated.apply)
      case Event.Deleted((k, v)) => cast[T](k, v).map(Event.Deleted.apply)
    }
  }
}

class TestClientEngineImpl[IO[_], T]
  (implicit io: Concurrent[IO], eq: Eq[T], clock: Clock[IO], res: ObjectResource[T])
  extends Engine[IO, TestClient[IO], T] with Logging
{
  private def _get(state: TestClient.State, id: Id[T]): Option[T] = {
    state.get(ResourceKey.id(id)).map(_.asInstanceOf[T])
  }

  override def read(c: TestClient[IO], id: Id[T]): IO[Option[T]] = c.readState.map(map => _get(map, id))

  private val _versionConflict = new TestClientError(ClientError.VersionConflict(new RuntimeException("version conflict")))

  private val _notFound = new TestClientError(ClientError.NotFound(new RuntimeException("not found")))

  override def write(c: TestClient[IO], t: T): IO[Unit] = {
    val nextVersion = {
      val newVersion = res.version(t) match {
        case "" => 1
        case other => other.toInt + 1
      }
      res.withVersion(t, newVersion.toString)
    }
    c.modifyState_ { stateMap =>
      val id = res.id(t)
      val key = ResourceKey.id(id)

      val update: IO[(TestClient.State, Option[Event[TestClient.Entry]])] = _get(stateMap, id) match {
        case None => {
          logger.debug("[{}] creating", res.id(t))
          io.pure((stateMap.updated(key, t), Some(Event.Updated((key, t)))))
        }
        case Some(existing) => {
          if (res.version(t) =!= res.version(existing)) {
            logger.debug("[{}] version conflict writing version {} (current version: {})", res.id(t), res.version(t), res.version(existing))
            io.raiseError(_versionConflict)
          } else if (res.isSoftDeleted(t) && res.finalizers(t).isEmpty) {
            logger.debug("[{}] soft-deleted resource has no remaining finalizers; deleting it", res.id(t), res.version(nextVersion))
            io.pure((stateMap.removed(key), Some(Event.Deleted((key, t)))))
          } else if (existing === t) {
            // we don't emit an event on a no-op change, otherwise we'd reconcile indefinitely
            logger.debug("[{}] no-op update", res.id(t))
            io.pure((stateMap, None))
          } else {
            logger.debug("[{}] updated (new version: {})", res.id(t), res.version(nextVersion))
            io.pure((stateMap.updated(key, nextVersion), Some(Event.Updated((key, nextVersion)))))
          }
        }
      }

      update.flatMap {
        case (newState, event) => {
          // in the case of deletion, we just pretend what you wrote is still there
          // (this is what k8s does, since GC is asynchronous)
          event.traverse(c.publish).as(newState)
        }
      }
    }
  }

  override def writeStatus[St](c: TestClient[IO], t: T, st: St)(implicit sub: HasStatus[T, St]): IO[Unit] = write(c, sub.withStatus(t, st))

  override def classifyError(e: Throwable): ClientError = e match {
    case ce: TestClientError => ce.e
    case other: Throwable => ClientError.Unknown(other)
  }

  override def delete(c: TestClient[IO], id: Id[T]): IO[Unit] = {
    c.modifyState_ { stateMap =>
      val key = ResourceKey.id(id)
      _get(stateMap, id) match {
        case None => io.raiseError(_notFound)
        case Some(existing) =>
          if (res.finalizers(existing).isEmpty) {
            val event: Event[TestClient.Entry] = Event.Deleted((key, existing))
            c.publish(event).as(stateMap.removed(key))
          } else {
            if (res.isSoftDeleted(existing)) {
              // no-op
              io.pure(stateMap)
            } else {
              clock.realTime(TimeUnit.SECONDS).flatMap { seconds =>
                val updated = res.softDeletedAt(existing, Instant.ofEpochSecond(seconds))
                val event: Event[TestClient.Entry] = Event.Updated((key, updated))
                c.publish(event).as(stateMap.updated(key, updated))
              }
            }
          }
      }
    }
  }

  override def listAndWatch(c: TestClient[IO], opts: ListOptions): IO[(List[T], fs2.Stream[IO, Event[T]])] = {
    if (opts != ListOptions.all) {
      logger.warn(s"Ignoring $opts (not implemented)")
    }
    for {
      resource <- c.topic.subscribeAwait(64).allocated
      stateMap <- c.readState
    } yield {
      val initial = stateMap.flatMap {
        case (k, v) => ResourceKey.cast[T](k, v).toList
      }.toList
      logger.debug("listAndWatch returning {} initial items", initial.length)
      val (updates, release) = resource
      (initial, updates.mapFilter(ResourceKey.castEvent[T]).onFinalize(release))
    }
  }
}