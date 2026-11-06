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

package avocet.impl.internal

import scala.util.hashing.MurmurHash3

/**
  * Hash functions used by the diff tape.
  *
  * A hash is only a fast rejection test: equal hashes are followed by an exact
  * byte comparison in `DiffRenderContext`. Tests use these functions to assert
  * that their fixed collision fixtures still collide.
  */
private[avocet] object Hashing {

  /** Hash of a tag, attribute or style name. */
  def nameHash(name: String): Int = name.hashCode

  /** Hash of a text node or attribute/style value. */
  def valueHash(value: String): Int = MurmurHash3.stringHash(value)
}
