package avocet

/**
  * @author Aleksey Fomkin <aleksey.fomkin@gmail.com>
  */
object IdBuilderTest extends utest.TestSuite {

  import utest._

  val tests = Tests {
    test("IdCounter") - {
      test("should increment id on first level") - {
        val counter = IdBuilder()
        counter.incId()
        counter.incId()
        counter.incId()
        val res = counter.mkString
        assert(res == "3")
      }
      test("should increment id on second level") - {
        val counter = IdBuilder()
        counter.incLevel()
        counter.incId()
        counter.incId()
        counter.incId()
        val res = counter.mkString
        assert(res == "0_3")
      }
      test("should increment id on third level") - {
        val counter = IdBuilder()
        counter.incLevel()
        counter.incLevel()
        counter.incId()
        counter.incId()
        counter.incId()
        val res = counter.mkString
        assert(res == "0_0_3")
      }
      test("should successfully decrement level") - {
        val counter = IdBuilder()
        counter.incId()
        counter.incLevel()
        counter.incLevel()
        counter.incId()
        counter.incId()
        counter.incId()
        counter.decLevel()
        counter.decLevel()
        val res = counter.mkString
        assert(res == "1")
      }
      test("should reset level on decrement") - {
        val counter = IdBuilder()
        counter.incLevel()
        counter.incId()
        counter.decLevel()
        counter.incLevel()
        val res = counter.mkString
        assert(res == "0_0")
      }
    }
  }
}


object IdBuilderLimitsTest extends utest.TestSuite {

  import utest._

  def expectCode(code: RenderException.Code)(f: => Unit): Unit = {
    val e = intercept[RenderException](f)
    assert(e.code == code)
  }

  val tests = Tests {
    test("sibling index stops at Short.MaxValue without wrapping") - {
      val counter = IdBuilder()
      var i = 0
      while (i < IdBuilder.MaxSiblings) { counter.incId(); i += 1 }
      assert(counter.mkString == "32767")
      expectCode(RenderException.Code.LimitExceeded)(counter.incId())
      assert(counter.mkString == "32767")
    }
    test("level stops at maxLevel and the failed call leaves the state untouched") - {
      val counter = IdBuilder(3)
      counter.incLevel()
      counter.incLevel()
      assert(counter.getLevel == 3)
      expectCode(RenderException.Code.LimitExceeded)(counter.incLevel())
      expectCode(RenderException.Code.LimitExceeded)(counter.ensureCanDescend())
      assert(counter.getLevel == 3)
      assert(counter.mkString == "0_0_0")
    }
    test("level underflow is an error instead of silent corruption") - {
      val counter = IdBuilder()
      expectCode(RenderException.Code.InvalidState)(counter.decLevel())
      expectCode(RenderException.Code.InvalidState)(counter.decLevelTmp())
    }
    test("invalid maxLevel is rejected") - {
      expectCode(RenderException.Code.InvalidArgument)(IdBuilder(0))
    }
  }
}
