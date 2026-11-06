package avocet

import avocet.impl.DiffRenderContext
import java.nio.ByteBuffer
import utest._

/**
  * A consumer compiled against 1.0.0 calls the three-argument
  * `DiffRenderContext.apply` and its `apply$default$1..3` methods by their
  * JVM signatures. They must keep existing next to the four-argument factory.
  */
object FactoryBinaryCompatTest extends TestSuite {

  val tests = Tests {
    "the 1.0.0 factory and its default-argument methods exist" - {
      val companion = DiffRenderContext.getClass
      val legacy = companion.getMethod("apply", classOf[Function2[_, _, _]], classOf[Int], classOf[Option[_]])
      assert(legacy.getReturnType == classOf[DiffRenderContext[_]])
      (1 to 3).foreach(i => companion.getMethod(s"apply$$default$$$i"))
      val instance = legacy.invoke(DiffRenderContext, (_: Id, _: Nothing) => (), Integer.valueOf(1024), None).asInstanceOf[DiffRenderContext[Nothing]]
      instance.openNode(XmlNs.html, "div")
      instance.closeNode("div")
      instance.finalizeDocument()
      instance.diff(DiffRenderContext.DummyChangesPerformer)
      val saved: ByteBuffer = instance.save()
      assert(saved.capacity() > 0)
    }
  }
}
