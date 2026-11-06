package avocet

import avocet.impl.internal.Hashing

/**
  * Fixed inputs whose hashes collide under the engine's hash functions.
  * Tests assert the fixture still collides before relying on it, so a
  * change of hash function cannot silently turn a collision test into an
  * ordinary one. No test searches for collisions at run time.
  */
object HashCollisionFixtures {

  /** Tag/attribute/style names with equal `Hashing.nameHash` (String.hashCode). */
  val nameA = "Aa"
  val nameB = "BB"

  /** Text/value strings with equal `Hashing.valueHash` (MurmurHash3). Found by the plan review. */
  val valueA = "t86388"
  val valueB = "t118091"

  /** Text children whose `div(text)` subtrees have equal content hashes (found by an offline search over `s0`, `s1`, ...). */
  val subtreeTextA = "s121742"
  val subtreeTextB = "s143709"

  def assertStillCollide(): Unit = {
    assert(nameA != nameB && Hashing.nameHash(nameA) == Hashing.nameHash(nameB),
      "name collision fixture no longer collides")
    assert(valueA != valueB && Hashing.valueHash(valueA) == Hashing.valueHash(valueB),
      "value collision fixture no longer collides")
  }
}
