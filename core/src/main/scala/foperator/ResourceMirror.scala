package foperator

import cats.Monad
import cats.effect.Concurrent
import cats.effect.concurrent.{MVar, MVar2}
import cats.implicits._
import foperator.internal.{Broadcast, IOUtil, Logging}
import foperator.types._
import fs2.{Chunk, Stream}

/**
 * ResourceMirror provides:
 *  - A snapshot of the current state of a set of resources
 *  - A stream tracking the ID of changed resources.
 *    Subscribers to this stream MUST NOT backpressure, as that could cause the
 *    watcher (and other observers) to fall behind.
 *    In practice, this is typically consumed by Dispatcher, which doesn't backpressure.
 */
trait ResourceMirror[IO[_], T] extends ReconcileSource[IO, T] {
  def all: IO[ResourceMirror.ResourceMap[T]]
  def active: IO[Map[Id[T], T]]
  def allValues: IO[List[ResourceState[T]]]
  def activeValues: IO[List[T]]
  def get(id: Id[T]): IO[Option[ResourceState[T]]]
  def getActive(id: Id[T]): IO[Option[T]]
}

object ResourceMirror extends Logging {
  type ResourceMap[T] = Map[Id[T], ResourceState[T]]

  private class Impl[IO[_], T](
    state: MVar2[IO, ResourceMap[T]],
    idStream: Stream[IO, Id[T]],
  )(implicit io: Monad[IO]) extends ResourceMirror[IO, T] {
    override def all: IO[Map[Id[T], ResourceState[T]]] = state.read
    override def active: IO[Map[Id[T], T]] = io.map(all)(_.mapFilter(ResourceState.active))
    override def allValues: IO[List[ResourceState[T]]] = all.map(_.values.toList)
    override def activeValues: IO[List[T]] = active.map(_.values.toList)
    override def get(id: Id[T]): IO[Option[ResourceState[T]]] = all.map(m => m.get(id))
    override def getActive(id: Id[T]): IO[Option[T]] = active.map(m => m.get(id))
    override def ids: Stream[IO, Id[T]] = idStream
  }

  private [foperator] def apply[IO[_], C, T, R](client: C, opts: ListOptions)(block: ResourceMirror[IO, T] => IO[R])
    (implicit io: Concurrent[IO], res: ObjectResource[T], e: Engine[IO, C, T]): IO[R] = {
    for {
      listAndWatch <- e.listAndWatch(client, opts)
      (initial, updates) = listAndWatch
      state <- MVar[IO].of(initial.map(obj => res.id(obj) -> ResourceState.of(obj)).toMap)
      trackedUpdates = trackState(state, updates).map(e => res.id(e.raw))
      topic <- Broadcast[IO, Id[T]]
      consume = trackedUpdates.through(topic.publish).compile.drain
      ids = injectInitial(state.read.map(_.keys.toList), topic)
      impl = new Impl(state, ids)
      result <- IOUtil.withBackground(IOUtil.nonTerminating(consume), block(impl))
    } yield result
  }

  private def injectInitial[IO[_], T](initial: IO[List[T]], topic: Broadcast[IO, T])(implicit io: Concurrent[IO]): Stream[IO, T] = {
    Stream.resource(topic.subscribeAwait(1)).flatMap { updates =>
      // first emit initial IDs, then follow with updates
      Stream.evalUnChunk(io.map(initial)(Chunk.seq)).append(updates)
    }
  }

  private def trackState[IO[_], T]
    (state: MVar2[IO, ResourceMap[T]], input: Stream[IO, Event[T]])(implicit
      io: Concurrent[IO],
      res: ObjectResource[T],
    ): Stream[IO, Event[T]] = {
    def transform(f: ResourceMap[T] => ResourceMap[T]) = {
      io.flatMap(state.take)((initial: ResourceMap[T]) => io.uncancelable(state.put(f(initial))))
    }

    input.evalTap[IO, Unit] { event =>
      val id = res.id(event.raw)
      val desc = s"${Event.desc(event)}($id, v${res.version(event.raw)})"
      io.delay(logger.debug("[{}] State applied {}", res.kind, desc))
    }.evalTap {
      case Event.Deleted(t) => transform(s => s.removed(res.id(t)))
      case Event.Updated(t) => transform(s => s.updated(res.id(t), ResourceState.of(t)))
    }
  }
}