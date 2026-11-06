/*
 * Copyright 2017-2020 Aleksey Fomkin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package avocet

import avocet.impl.DiffRenderContext
import avocet.impl.DiffRenderContext.FastChangesPerformer
import avocet.impl.TrimPolicy

import java.nio.ByteBuffer
import scala.collection.mutable
import scala.util.control.NonFatal

/**
  * Result of one [[RenderRoot.render]] call. The three cases are the three
  * failure boundaries of the lifecycle contract:
  *
  *  - [[RenderOutcome.Published]]: the document was diffed against the
  *    baseline, every change reached the performer, and the new baseline and
  *    misc entries are now the published generation.
  *  - [[RenderOutcome.Rejected]]: the template, the validator or
  *    finalization failed. Nothing was published; the previous baseline and
  *    misc entries stay in force and the root stays usable.
  *  - [[RenderOutcome.Invalidated]]: the performer failed while changes were
  *    being applied. Backend writes are not rolled back, so the baseline no
  *    longer describes the backend; the root refuses further renders until
  *    [[RenderRoot.reset]] or [[RenderRoot.dispose]].
  *
  * Side effects performed by application code inside the template are never
  * undone by any outcome.
  */
sealed trait RenderOutcome {
  def isPublished: Boolean
  /** The failure of a `Rejected` or `Invalidated` outcome. */
  def failure: Option[Throwable]
}

object RenderOutcome {

  final case class Published(generation: Int) extends RenderOutcome {
    def isPublished: Boolean = true
    def failure: Option[Throwable] = None
  }

  final case class Rejected(cause: Throwable) extends RenderOutcome {
    def isPublished: Boolean = false
    def failure: Option[Throwable] = Some(cause)
  }

  final case class Invalidated(cause: Throwable) extends RenderOutcome {
    def isPublished: Boolean = false
    def failure: Option[Throwable] = Some(cause)
  }
}

/**
  * High-level render lifecycle over [[avocet.impl.DiffRenderContext]].
  *
  * One root owns one diff context, one performer and the misc entries of the
  * published generation. `render` runs the whole sequence
  * `render -> validate -> finalize -> diff -> publish` and reports the outcome
  * as a [[RenderOutcome]] instead of leaving the caller to order the
  * low-level calls. The low-level context stays available for integrations
  * that need it; this class is the recommended path.
  *
  * States ([[RenderRoot.State]]):
  *
  *  - `Idle`: ready to render.
  *  - `Rendering`: a render is in progress. A nested `render` (from a
  *    performer or misc callback) throws `INVALID_STATE`.
  *  - `Invalid`: a performer failed mid-apply. Only `reset()` and
  *    `dispose()` are allowed.
  *  - `Disposed`: buffers released; every other call throws.
  *
  * Misc entries (events, references) reported by the template are staged
  * during the render and become visible through [[misc]] only after a
  * successful publish, so a failed render never exposes handlers for a tree
  * that does not exist. Each publish increments [[generation]]; an
  * integration can tag handlers with it and reject stale events.
  *
  * Thread confinement: a root is single-threaded. Calls must come from one
  * thread at a time; the class does not synchronize.
  *
  * Validation ([[Validation]]) is opt-in. When off, no validation structure
  * is allocated.
  */
final class RenderRoot[M] private (performer: FastChangesPerformer,
                                   validation: Validation,
                                   initialBufferSize: Int,
                                   savedBuffer: Option[ByteBuffer],
                                   trimPolicy: TrimPolicy) {

  import RenderRoot.State

  private val staged = mutable.ArrayBuffer.empty[(Id, M)]
  private var context: DiffRenderContext[M] =
    DiffRenderContext[M]((id, m) => staged += ((id, m)), initialBufferSize, savedBuffer, trimPolicy)
  // A snapshot may hold a finalized candidate that was never published; the root starts from the baseline only.
  if (savedBuffer.isDefined) context.reset()
  private var validator: StatefulValidatingRenderContext[M] =
    if (validation.enabled) ValidatingRenderContext.stateful(context, validation) else null
  private var currentState: State = State.Idle
  private var currentGeneration = 0
  private var publishedMisc: Vector[(Id, M)] = Vector.empty

  def state: State = currentState

  /** Number of successful publishes (and resets) so far; 0 before the first. */
  def generation: Int = currentGeneration

  /** Misc entries of the published generation, in template order. Empty after `reset()`. */
  def misc: Vector[(Id, M)] = publishedMisc

  /** Renders `doc`; see [[renderWith]]. */
  def render(doc: Document.Node[M]): RenderOutcome = renderWith(rc => doc(rc))

  /**
    * Runs one render cycle with `template`. The context handed to the
    * template is the validating wrapper when validation is on, otherwise the
    * diff context itself; both expose `currentId` and friends.
    */
  def renderWith(template: StatefulRenderContext[M] => Unit): RenderOutcome = {
    requireState("render", State.Idle)
    currentState = State.Rendering
    staged.clear()
    // Stage 1: construct and finalize the candidate. Any failure here keeps the baseline.
    val target: StatefulRenderContext[M] = if (validator == null) context else { validator.reset(); validator }
    try {
      template(target)
      if (validator != null) {
        validator.complete()
        if (validator.errors.nonEmpty) throw new ValidationException(validator.errors, validator.truncated)
      }
      context.finalizeDocument()
    } catch {
      case NonFatal(e) =>
        discardCandidate()
        currentState = State.Idle
        return RenderOutcome.Rejected(withDiagnostics(e))
      case e: Throwable =>
        discardCandidate()
        currentState = State.Idle
        throw e
    }
    // Stage 2: apply. Performer writes cannot be rolled back.
    try context.diff(performer)
    catch {
      case NonFatal(e) =>
        staged.clear()
        currentState = State.Invalid
        return RenderOutcome.Invalidated(e)
      case e: Throwable =>
        staged.clear()
        currentState = State.Invalid
        throw e
    }
    // Stage 3: publish baseline and misc entries together.
    context.swap()
    publishedMisc = staged.toVector
    staged.clear()
    currentGeneration += 1
    currentState = State.Idle
    RenderOutcome.Published(currentGeneration)
  }

  /** In collect mode the inner context may throw before the collected errors are seen; attach them. */
  private def withDiagnostics(e: Throwable): Throwable =
    if (validator != null && validator.errors.nonEmpty && !e.isInstanceOf[ValidationException])
      new ValidationException(validator.errors, validator.truncated, e)
    else e

  private def discardCandidate(): Unit = {
    staged.clear()
    context.reset()
  }

  /**
    * Discards the baseline and the published misc entries and returns to
    * `Idle`: the next render replays the whole document against an empty
    * baseline. This is the recovery path after `Invalidated`; the backend
    * must be able to accept a full rebuild (for the DOM performer that means
    * the root node is recreated).
    */
  def reset(): Unit = {
    requireState("reset", State.Idle, State.Invalid)
    staged.clear()
    context.reset()
    context.swap()
    publishedMisc = Vector.empty
    currentGeneration += 1
    currentState = State.Idle
  }

  /** Snapshot of the baseline in saved-buffer format; see `DiffRenderContext.save`. */
  def save(): ByteBuffer = {
    requireState("save", State.Idle)
    context.save()
  }

  /** Shrinks the retained buffers to fit the baseline; see `DiffRenderContext.trim`. */
  def trim(): Boolean = {
    requireState("trim", State.Idle)
    context.trim()
  }

  /** Bytes retained by the render buffers. */
  def bufferCapacity: Int = {
    requireNotDisposed("bufferCapacity")
    context.bufferCapacity
  }

  /** Releases the buffers and misc entries. Idempotent; every later call except `state` throws. */
  def dispose(): Unit = if (currentState != State.Disposed) {
    if (currentState == State.Rendering)
      throw RenderException.invalidState("dispose() called while a render is in progress")
    staged.clear()
    publishedMisc = Vector.empty
    context = null
    validator = null
    currentState = State.Disposed
  }

  private def requireNotDisposed(op: String): Unit =
    if (currentState == State.Disposed) throw RenderException.invalidState(s"$op on a disposed root")

  private def requireState(op: String, allowed: State*): Unit =
    if (!allowed.contains(currentState)) currentState match {
      case State.Rendering => throw RenderException.invalidState(s"$op called while a render is in progress")
      case State.Invalid => throw RenderException.invalidState(s"$op on an invalid root; call reset() or dispose()")
      case State.Disposed => throw RenderException.invalidState(s"$op on a disposed root")
      case State.Idle => throw RenderException.invalidState(s"$op is not allowed while idle")
    }
}

object RenderRoot {

  sealed trait State

  object State {
    case object Idle extends State
    case object Rendering extends State
    case object Invalid extends State
    case object Disposed extends State
  }

  def apply[M](performer: FastChangesPerformer,
               validation: Validation = Validation.off,
               initialBufferSize: Int = DiffRenderContext.DefaultDiffRenderContextBufferSize,
               savedBuffer: Option[ByteBuffer] = None,
               trimPolicy: TrimPolicy = TrimPolicy.Manual): RenderRoot[M] =
    new RenderRoot[M](performer, validation, initialBufferSize, savedBuffer, trimPolicy)
}
