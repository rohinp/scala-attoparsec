package attoparsec

import scalaz._
import scalaz.Scalaz._
import scalaz.\/._

import language.implicitConversions
import language.higherKinds

abstract class Parser[+A] { m =>
  import Parser._
  import Parser.Internal._
  def apply[R](st0: State, kf:Failure[R], ks: Success[A,R]): Result[R]
  def infix(s: String) = "(" + m.toString + ") " + s

  def flatMap[B](f: A => Parser[B]): Parser[B] = new Parser[B] {
    override def toString = m infix "flatMap ..."
    def apply[R](st0: State, kf: Failure[R], ks: Success[B,R]): Result[R] =
      m(st0,kf,(s:State, a:A) => f(a)(s,kf,ks))
  }
  def map[B](f: A => B): Parser[B] = new Parser[B] {
    override def toString = m infix "map ..."
    def apply[R](st0: State, kf: Failure[R], ks: Success[B,R]): Result[R] =
      m(st0,kf,(s:State, a:A) => ks(s,f(a)))
  }

  class WithFilter(p: A => Boolean) extends Parser[A] { wf =>
    override def toString = m infix "withFilter ..."
    def apply[R](st0: State, kf: Failure[R], ks: Success[A,R]): Result[R] =
      m(st0,kf,(s:State, a:A) => if (p(a)) ks(s,a) else kf(s, Nil, "withFilter"))
    override def map[B](f: A => B) = m filter p map f
    override def flatMap[B](f: A => Parser[B]) = new Parser[B] {
      override def toString = wf.toString
      def apply[R](st0: State, kf: Failure[R], ks: Success[B,R]): Result[R] =
        m(st0,kf,(s:State, a:A) => if (p(a)) f(a)(s,kf,ks) else kf(s, Nil, "withFilter"))
    }
    override def withFilter(q: A => Boolean): WithFilter =
      new WithFilter(x => p(x) && q(x))
    override def filter(q: A => Boolean): WithFilter =
      new WithFilter(x => p(x) && q(x))
  }
  def withFilter(p: A => Boolean): WithFilter = new WithFilter(p)
  def filter(p: A => Boolean): Parser[A]      = new WithFilter(p)

  final def ~> [B](n: => Parser[B]): Parser[B] = new Parser[B] {
    override def toString = m infix ("~> " + n)
    def apply[R](st0: State, kf: Failure[R], ks: Success[B,R]): Result[R] =
      m(st0,kf,(s:State, a: A) => n(s, kf, ks))
  }
  final def <~ [B](n: => Parser[B]): Parser[A] = new Parser[A] {
    override def toString = m infix ("<~ " + n)
    def apply[R](st0: State, kf: Failure[R], ks: Success[A,R]): Result[R] =
      m(st0,kf,(st1:State, a: A) => n(st1, kf, (st2: State, b: B) => ks(st2, a)))
  }

  final def ~ [B](n: Parser[B]): Parser[(A,B)] = new Parser[(A,B)] {
    override def toString = m infix ("~ " + n)
    def apply[R](st0: State, kf: Failure[R], ks: Success[(A,B),R]): Result[R] =
      m(st0,kf,(st1:State, a: A) => n(st1, kf, (st2: State, b: B) => ks(st2, (a, b))))
  }
  final def | [B >: A](n: => Parser[B]): Parser[B] = new Parser[B] {
    override def toString = m infix ("| ...")
    def apply[R](st0: State, kf: Failure[R], ks: Success[B,R]): Result[R] =
      m(st0.noAdds, (st1: State, stack: List[String], msg: String) => n(st0 + st1, kf, ks), ks)
  }
  final def cons [B >: A](n: => Parser[List[B]]): Parser[List[B]] = m flatMap (x => n map (xs => x :: xs))
  final def || [B >: A](n: => Parser[B]): Parser[A \/ B] = new Parser[A \/ B] {
    override def toString = m infix ("|| " + n)
    def apply[R](st0: State, kf: Failure[R], ks: Success[A \/ B, R]): Result[R] =
      m(
        st0.noAdds,
        (st1: State, stack: List[String], msg: String) => n (st0 + st1, kf, (st1: State, b: B) => ks(st1, right(b))),
        (st1: State, a: A) => ks(st1, left(a))
      )
  }
  final def matching[B](f: PartialFunction[A,B]): Parser[B] = m.filter(f isDefinedAt _).map(f)

  final lazy val ? : Parser[Option[A]] = opt(m)
  final lazy val + : Parser[List[A]] = many1(m)
  final lazy val * : Parser[List[A]] = many(m)

  final def *(s: Parser[Any]): Parser[List[A]] = sepBy(m,s)
  final def +(s: Parser[Any]): Parser[List[A]] = sepBy1(m,s)

  final def parse(b: String): ParseResult[A] = m(b, Fail(_,_,_), Done(_,_)).translate

  final def parseOnly[A](s: String) = m(State(s, "", true), Fail(_,_,_), Done(_,_)) match {
    case Fail(_, _, e) => left(e)
    case Done(_, a) => right(a)
    case _ => sys.error("parseOnly: Parser returned a partial result")
  }

  final def as(s: => String): Parser[A] = new Parser[A] {
    override def toString = s
    def apply[R](st0: State, kf: Failure[R], ks: Success[A,R]): Result[R] =
      m(st0, (st1: State, stack: List[String], msg: String) => kf(st1, s :: stack, msg), ks)
  }
  final def asOpaque(s: => String): Parser[A] = new Parser[A] {
    override def toString = s
    def apply[R](st0: State, kf: Failure[R], ks: Success[A,R]): Result[R] =
      m(st0, (st1: State, stack: List[String], msg: String) => kf(st1, Nil, "Failure reading:" + s), ks)
  }

}

sealed abstract class ParseResult[+A] {
  def map[B](f: A => B): ParseResult[B]
  def feed(s: String): ParseResult[A]
  def option: Option[A]
  def either: String \/ A
  def done: ParseResult[A] = feed("")
}

object ParseResult {
  case class Fail(input: String, stack: List[String], message: String) extends ParseResult[Nothing] {
    def map[B](f: Nothing => B) = Fail(input, stack, message)
    def feed(s: String) = this
    override def done = this
    def option = None
    def either = left(message)
  }
  case class Partial[+T](k: String => ParseResult[T]) extends ParseResult[T] {
    def map[B](f: T => B) = Partial(s => k(s).map(f))
    def feed(s: String) = k(s)
    def option = None
    def either = left("incomplete input")
  }
  case class Done[+T](input: String, result: T) extends ParseResult[T] {
    def map[B](f: T => B) = Done(input, f(result))
    def feed(s: String) = Done(input + s, result)
    override def done = this
    def option = Some(result)
    def either = right(result)
  }

  implicit def translate[T](r: Parser.Internal.Result[T]) : ParseResult[T] = r.translate
  implicit def option[T](r: ParseResult[T]): Option[T] = r.option
  implicit def either[T](r: ParseResult[T]): String \/ T = r.either
}

object Parser {

  object Internal {
    sealed abstract class Result[+T] {
      def translate: ParseResult[T]
    }
    case class Fail(input: State, stack: List[String], message: String) extends Result[Nothing] {
      def translate = ParseResult.Fail(input.input, stack, message)
      def push(s: String) = Fail(input, stack = s :: stack, message)
    }
    case class Partial[+T](k: String => Result[T]) extends Result[T] {
      def translate = ParseResult.Partial(a => k(a).translate)
    }
    case class Done[+T](input: State, result: T) extends Result[T] {
      def translate = ParseResult.Done(input.input, result)
    }
  }
  import Internal._

  implicit lazy val Monad : Monad[Parser] = new Monad[Parser] {
    def point[A](a: => A): Parser[A] = ok(a)
    def bind[A,B](ma: Parser[A])(f: A => Parser[B]) = ma flatMap f
  }

  implicit lazy val Plus: PlusEmpty[Parser] = new PlusEmpty[Parser] {
    def plus[A](a: Parser[A], b: => Parser[A]): Parser[A] = a | b
    def empty[A]: Parser[A] = err("zero")
  }
  implicit def Monoid[A]: Monoid[Parser[A]] = new Monoid[Parser[A]] {
    def append(s1: Parser[A], s2: => Parser[A]): Parser[A] = s1 | s2
    val zero: Parser[A] = err("zero")
  }

  case class State(input: String, added: String, complete: Boolean) {
    def +(rhs: State) = State(input + rhs.added, added + rhs.added, complete | rhs.complete)
    def +(rhs: String) = State(input + rhs, added + rhs, complete)
    def completed: State = copy (complete = true)
    def noAdds: State = copy (added = "")
  }

  implicit def stateString(s: State): String = s.input
  implicit def stringState(s: String): State = State(s, "", false)

  type Failure[+R] = (State,List[String],String) => Result[R]
  type Success[-A,+R] = (State, A) => Result[R]

  def ok[A](a: A): Parser[A] = new Parser[A] {
    override def toString = "ok(" + a + ")"
    def apply[R](st0: State, kf: Failure[R], ks: Success[A,R]): Result[R] =
      ks(st0,a)
  }

  def err(what: String): Parser[Nothing] = new Parser[Nothing] {
    override def toString = "err(" + what + ")"
    def apply[R](st0: State, kf: Failure[R], ks: Success[Nothing,R]): Result[R] =
      kf(st0,Nil, "Failed reading: " + what)
  }

  def prompt[R](st0: State, kf: State => Result[R], ks: State => Result[R]) = Partial[R](s =>
    if (s == "") kf(st0 copy (complete = true))
    else ks(st0 + s)
  )

  lazy val demandInput: Parser[Unit] = new Parser[Unit] {
    override def toString = "demandInput"
    def apply[R](st0: State, kf: Failure[R], ks: Success[Unit,R]): Result[R] =
      if (st0.complete)
        kf(st0,List("demandInput"),"not enough bytes")
      else
        prompt(st0, st => kf(st,List("demandInput"),"not enough bytes"), a => ks(a,()))
  }

  def ensure(n: Int): Parser[Unit] = new Parser[Unit] {
    override def toString = "ensure(" + n + ")"
    def apply[R](st0: State, kf: Failure[R], ks: Success[Unit,R]): Result[R] =
      if (st0.input.length >= n)
        ks(st0,())
      else
        (demandInput ~> ensure(n))(st0,kf,ks)
  }

  lazy val wantInput: Parser[Boolean] = new Parser[Boolean] {
    override def toString = "wantInput"
    def apply[R](st0: State, kf: Failure[R], ks: Success[Boolean,R]): Result[R] =
      if (st0.input != "")   ks(st0,true)
      else if (st0.complete) ks(st0,false)
      else prompt(st0, a => ks(a,false), a => ks(a,true))
  }

  lazy val atEnd: Parser[Boolean] = atEnd map (!_)

  lazy val get: Parser[String] = new Parser[String] {
    override def toString = "get"
    def apply[R](st0: State, kf: Failure[R], ks: Success[String,R]): Result[R] =
      ks(st0,st0.input)
  }

  def put(s: String): Parser[Unit] = new Parser[Unit] {
    override def toString = "put(" + s + ")"
    def apply[R](st0: State, kf: Failure[R], ks: Success[Unit,R]): Result[R] =
      ks(st0 copy (input = s), ())
  }

  // attoparsec try
  def attempt[T](p: Parser[T]): Parser[T] = new Parser[T] {
    override def toString = "attempt(" + p + ")"
    def apply[R](st0: State, kf: Failure[R], ks: Success[T,R]): Result[R] =
      p(st0.noAdds, (st1: State, stack: List[String], msg: String) => kf(st0 + st1, stack, msg), ks)
  }

  def satisfy(p: Char => Boolean) = elem(p, "satisfy(...)"): Parser[Char]

  def elem(p: Char => Boolean, what: => String = "elem(...)"): Parser[Char] =
    ensure(1) ~> get flatMap (s => {
      val c = s.charAt(0)
      if (p(c)) put(s.substring(1)) ~> ok(c)
      else err(what)
    }) asOpaque what

  def skip(s: String, p: Char => Boolean, what: => String = "skip(...)"): Parser[Unit] =
    ensure(1) ~> get flatMap (s => {
      if (p(s.charAt(0))) put(s.substring(1))
      else err(what)
    }) asOpaque what

  def takeWith(n: Int, p: String => Boolean, what: => String = "takeWith(...)"): Parser[String] =
    ensure(n) ~> get flatMap (s => {
      val w = s.substring(0,n)
      if (p(w)) put(s.substring(n)) ~> ok(w)
      else err(what)
    }) asOpaque what

  def take(n: Int): Parser[String] = takeWith(n, _ => true, "take(" + n + ")")

  lazy val anyChar: Parser[Char] = satisfy(_ => true)

  def notChar(c: Char): Parser[Char] = satisfy(_ != c) as ("not '" + c + "'")

  def takeWhile(p: Char => Boolean): Parser[String] = {
    def go(acc: List[String]): Parser[List[String]] = for {
      x <- get
      (h, t) = x span p
      _ <- put(t)
      r <- if (t.isEmpty) for {
        input <- wantInput
        r <- if (input) go(h :: acc)
             else ok(h :: acc)
      } yield r else ok(h :: acc)
    } yield r
    go(Nil).map(_.reverse.concatenate)
  }

  lazy val takeRest: Parser[List[String]] = {
    def go(acc: List[String]): Parser[List[String]] = for {
      input <- wantInput
      r <- if (input) for {
        s <- get
        _ <- put("")
        r <- go(s :: acc)
      } yield r else ok(acc.reverse)
    } yield r
    go(Nil)
  }

  lazy val takeText: Parser[String] =
    takeRest map (_.concatenate)

  // TODO: Add this to Scalaz
  def when[M[_]:Monad](b: Boolean)(m: M[Unit]) =
    if (b) m else implicitly[Monad[M]].pure(())

  def takeWhile1(p: Char => Boolean): Parser[String] = for {
    _ <- get map (_.isEmpty) flatMap (when(_)(demandInput))
    s <- get
    (h, t) = s span p
    _ <- when(h.isEmpty)(err("takeWhile1"):Parser[Unit])
    _ <- put(t)
    r <- if (t.isEmpty) takeWhile(p).map(h + _) else ok(h)
  } yield r

  implicit def char(c: Char): Parser[Char] = elem(_==c, "'" + c.toString + "'")
  implicit def string(s: String): Parser[String] = takeWith(s.length, _ == s, "\"" + s + "\"")

  lazy val signedInt: Parser[BigInt] =
    (char('-') ~> decimal).map(- _) | char('+') ~> decimal | decimal

  lazy val scientific: Parser[BigDecimal] = for {
    positive <- satisfy(c => c == '-' || c == '+').map(_ == '+') | ok(true)
    n <- decimal
    s <- (satisfy(_ == '.') ~> takeWhile(_.isDigit).map(f =>
      BigDecimal(n + "." + f))) | ok(BigDecimal(n))
    sCoeff = if (positive) s else (- s)
    r <- satisfy(c => c == 'e' || c == 'E') ~>
         signedInt.flatMap(x =>
           if (x > Int.MaxValue) err(s"Exponent too large: $x")
           else ok(s * BigDecimal(10).pow(x.toInt))) | ok(sCoeff)
  } yield r

  private def addDigit(a: BigInt, c: Char) = a * 10 + (c - 48)

  lazy val decimal: Parser[BigInt] =
    takeWhile1(_.isDigit).map(_.foldLeft(BigInt(0))(addDigit))

  def stringTransform(f: String => String, s: String, what: => String = "stringTransform(...)"): Parser[String] =
    takeWith(s.length, f(_) == f(s), what)

  lazy val endOfInput: Parser[Unit] = new Parser[Unit] {
    override def toString = "endOfInput"
    def apply[R](st0: State, kf: Failure[R], ks: Success[Unit,R]): Result[R] =
      if (st0.input == "") {
        if (st0.complete)
          ks(st0,())
        else
          demandInput(
            st0,
            (st1: State, stack: List[String], msg: String) => ks(st0 + st1,()),
            (st1: State, u: Unit) => kf(st0 + st1,Nil,"endOfInput")
          )
      } else kf(st0,Nil,"endOfInput")
  }
  def phrase[A](p: Parser[A]): Parser[A] = p <~ endOfInput as ("phrase" + p)

  // TODO: return a parser of a reducer of A
  def many[A](p: => Parser[A]): Parser[List[A]] = {
    lazy val many_p : Parser[List[A]] = (p cons many_p) | ok(Nil)
    many_p as ("many(" + p + ")")
  }

  def many1[A](p: => Parser[A]): Parser[List[A]] = p cons many(p)

  def manyTill[A](p: Parser[A], q: Parser[Any]): Parser[List[A]] = {
    lazy val scan : Parser[List[A]] = (q ~> ok(Nil)) | (p cons scan)
    scan as ("manyTill(" + p + "," + q + ")")
  }

  def skipMany1(p: Parser[Any]): Parser[Unit] = (p ~> skipMany(p)) as ("skipMany1(" + p + ")")

  def skipMany(p: Parser[Any]): Parser[Unit] = {
    lazy val scan : Parser[Unit] = (p ~> scan) | ok(())
    scan as ("skipMany(" + p + ")")
  }

  def sepBy[A](p: Parser[A], s: Parser[Any]): Parser[List[A]] =
    (p cons ((s ~> sepBy1(p,s)) | ok(Nil))) | ok(Nil) as ("sepBy(" + p + "," + s + ")")

  def sepBy1[A](p: Parser[A], s: Parser[Any]): Parser[List[A]] = {
    lazy val scan : Parser[List[A]] = (p cons ((s ~> scan) | ok(Nil)))
    scan as ("sepBy1(" + p + "," + s + ")")
  }

  def choice[A](xs : Parser[A]*) : Parser[A] =
    (err("choice").asInstanceOf[Parser[A]] /: xs)(_ | _) as ("choice(" + xs.toString + " :_*)")

  def choice[A](xs : TraversableOnce[Parser[A]]) : Parser[A] =
    (err("choice").asInstanceOf[Parser[A]] /: xs)(_ | _) as ("choice(...)")

  def opt[A](m: Parser[A]): Parser[Option[A]] = (attempt(m).map(some(_)) | ok(none)) as ("opt(" + m + ")")

  sealed trait Scan[+S]
  case class Continue[S](s: S) extends Scan[S]
  case class Finished(n: Int, s: String) extends Scan[Nothing]

  def scan[S](s: S)(p: (S, Char) => Option[S]): Parser[String] = {
    def scanner(s: S, n: Int, t: String): Scan[S] = {
      if (t.isEmpty) Continue(s)
      else p(s, t.head) match {
        case Some(s) => scanner(s, n + 1, t.tail)
        case None => Finished(n, t)
      }
    }
    def go(acc: List[String], s: S): Parser[List[String]] = for {
      input <- get
      r <- scanner(s, 0, input) match {
        case Continue(sp) => for {
          _ <- put("")
          more <- wantInput
          r <- if (more) go(input :: acc, sp) else ok(input :: acc)
        } yield r
        case Finished(n, t) => put(t).flatMap(_ => ok(input.take(n) :: acc))
      }
    } yield r
    for {
      chunks <- go(Nil, s)
      r <- chunks match {
        case List(x) => ok(x)
        case xs => ok(xs.reverse.concatenate)
      }
    } yield r
  }

  def parse[A](m: Parser[A], init: String): ParseResult[A] = m parse init
  def parse[M[_]:Monad, A](m: Parser[A], refill: M[String], init: String): M[ParseResult[A]] = {
    def step[A] (r: Result[A]): M[ParseResult[A]] = r match {
      case Partial(k) => refill flatMap (a => step(k(a)))
      case x => x.translate.pure[M]
    }
    step(m(init,Fail(_,_,_),Done(_,_)))
  }

  def parseAll[A](m: Parser[A], init: String) = phrase(m) parse init
  def parseAll[M[_]:Monad, A](m: Parser[A], refill: M[String], init: String): M[ParseResult[A]] =
    parse[M,A](phrase(m), refill, init)
}
