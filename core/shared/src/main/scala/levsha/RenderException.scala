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

/**
  * Raised when rendering input violates a documented limit, name policy or
  * lifecycle rule, or when a saved buffer fails validation.
  *
  * Messages carry a stable code, sizes and positions only. They never include
  * text, attribute values or whole names, so they are safe to log.
  */
class RenderException private[avocet] (val code: RenderException.Code, message: String)
  extends RuntimeException(s"${code.name}: $message")

object RenderException {

  sealed abstract class Code(val name: String)

  object Code {
    /** A dynamic tag, attribute or style name is not allowed by the name policy. */
    case object InvalidName extends Code("INVALID_NAME")
    /** A declared resource limit (depth, siblings, lengths, buffer size) was exceeded. */
    case object LimitExceeded extends Code("LIMIT_EXCEEDED")
    /** A lifecycle method was called in the wrong order. */
    case object InvalidState extends Code("INVALID_STATE")
    /** A saved render buffer is malformed or unsupported. */
    case object InvalidSavedBuffer extends Code("INVALID_SAVED_BUFFER")
    /** A constructor or method argument is invalid. */
    case object InvalidArgument extends Code("INVALID_ARGUMENT")
    /** A document failed opt-in validation; see [[ValidationException]] for the coded errors. */
    case object InvalidDocument extends Code("INVALID_DOCUMENT")
  }

  def invalidName(kind: String, reason: String): RenderException =
    new RenderException(Code.InvalidName, s"invalid $kind name: $reason")

  def limit(reason: String): RenderException =
    new RenderException(Code.LimitExceeded, reason)

  def invalidState(reason: String): RenderException =
    new RenderException(Code.InvalidState, reason)

  def invalidSavedBuffer(reason: String): RenderException =
    new RenderException(Code.InvalidSavedBuffer, reason)

  def invalidArgument(reason: String): RenderException =
    new RenderException(Code.InvalidArgument, reason)
}
