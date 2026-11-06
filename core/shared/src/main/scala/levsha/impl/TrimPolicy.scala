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

package avocet.impl

import avocet.RenderException

/**
  * Buffer retention policy of [[DiffRenderContext]]. The backing buffer
  * grows to hold the largest document rendered so far; this policy decides
  * when it shrinks again.
  */
sealed trait TrimPolicy

object TrimPolicy {

  /** Never shrinks on its own; the owner calls `trim()` when it sees fit. */
  case object Manual extends TrimPolicy

  /**
    * Shrinks after `afterSwaps` consecutive publishes whose baseline used
    * less than `1/slack` of a buffer half. The hysteresis avoids
    * reallocating on every small fluctuation while a large tree that
    * disappears for good releases its memory after a bounded number of
    * cycles. The new size still holds the baseline twice over.
    */
  final case class Automatic(afterSwaps: Int = 8, slack: Int = 4) extends TrimPolicy {
    if (afterSwaps < 1) throw RenderException.invalidArgument("afterSwaps must be positive")
    if (slack < 2) throw RenderException.invalidArgument("slack must be at least 2")
  }
}
