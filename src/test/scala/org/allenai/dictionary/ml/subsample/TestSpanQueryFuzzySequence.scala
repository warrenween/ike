package org.allenai.dictionary.ml.subsample

import nl.inl.blacklab.search.Searcher
import org.allenai.common.testkit.{ UnitSpec, ScratchDirectory }
import org.allenai.dictionary._
import org.allenai.dictionary.index.TestData
import scala.collection.JavaConverters._

class TestSpanQueryFuzzySequence extends UnitSpec with ScratchDirectory {
  TestData.createTestIndex(scratchDir)
  val searcher = TestData.testSearcher(scratchDir)

  def runNoisySeqQuery(searcher: Searcher, seq: Seq[QExpr],
    minMatches: Int,
    maxMatches: Int, allowDeletions: Boolean = false
  ): Seq[String] = {
    val spanQueries = seq.map(x => searcher.createSpanQuery(
      BlackLabSemantics.blackLabQuery(x).rewrite()
    ))
    // ignoreLastToken should really be true for the test data, but for reasons not fully understood
    // keeping it true leads to errors on the test data but works on the other corpus
    val ignoreLastToken = true
    val query = new SpanQueryFuzzySequence(spanQueries, minMatches, maxMatches, allowDeletions,
      ignoreLastToken, Seq())
    val hits = searcher.find(query)
    hits.asScala.map(hit => {
      hits.getKwic(hit).getMatch("word").asScala.mkString(" ")
    }).toSeq
  }

  "SpanQueryFuzzySequence" should "pass on toy data" in {
    assertResult(Seq("I like mango", "I hate those")) {
      runNoisySeqQuery(searcher, Seq(QWord("I"), QWord("like"), QWord("mango")), 1, 3)
    }
    assertResult(Seq("I like mango .")) {
      runNoisySeqQuery(searcher, Seq(QWord("I"), QWord("those"), QPos("NN"), QWord(".")), 3, 4)
    }
    assertResult(Seq("I like")) {
      runNoisySeqQuery(searcher, Seq(QWildcard(), QWord("I"), QWord("like")), 2, 3)
    }
    assertResult(Seq("mango .")) {
      runNoisySeqQuery(searcher, Seq(QWord("mango"), QWord("."), QWildcard()), 2, 3)
    }
    assertResult(Seq("I like", "mango .")) {
      runNoisySeqQuery(searcher, Seq(QWord("mango"), QWord("."), QWord("I"), QWord("like")), 2, 4,
        true)
    }
    assertResult(Seq("I like mango", "I hate those")) {
      runNoisySeqQuery(searcher, Seq(QWord("I"), QWord("hate"), QWord("mango")), 2, 3)
    }
    assertResult(Seq("I hate those")) {
      runNoisySeqQuery(searcher, Seq(QWord("I"), QPos("VBP"), QWord("mango")), 2, 2)
    }
    assertResult(Seq("I like mango", "like mango .", "taste not great")) {
      runNoisySeqQuery(searcher, Seq(
        QDisj(Seq(QWord("I"), QWord("like"), QWord("taste"))),
        QDisj(Seq(QWord("like"), QWord("mango"))),
        QDisj(Seq(QWord("great")))
      ), 2, 3)
    }
  }

  def getFuzzySeqCaptureGroups(searcher: Searcher, seq: Seq[QExpr],
    min: Int, max: Int, captures: Seq[CaptureSpan],
    captureEdits: Boolean = false): Seq[Seq[String]] = {
    val spanQueries = seq.map(x => searcher.
      createSpanQuery(BlackLabSemantics.blackLabQuery(x).rewrite()))
    val query = new SpanQueryFuzzySequence(spanQueries, min, max, captureEdits,
      searcher.getIndexStructure.alwaysHasClosingToken, captures)
    val hits = searcher.find(query)
    hits.asScala.map(hit => {
      val kwic = hits.getKwic(hit)
      val tokens = kwic.getMatch("word")
      hits.getCapturedGroups(hit).filter(_ != null).map(span => {
        val captureLength = math.abs(span.end) - math.abs(span.start)
        val tokenStart = math.abs(span.start) - kwic.getHitStart
        val str = tokens.subList(tokenStart, tokenStart + captureLength).asScala.mkString(" ")
        if (span.end < 0) {
          "MISS: " + str
        } else {
          str
        }
      }).toSeq
    }).toSeq
  }

  it should "return correct capture groups" in {
    assertResult(Seq(
      Seq("I", "like mango", "I like mango"),
      Seq("I", "hate those", "I hate those")
    )) {
      getFuzzySeqCaptureGroups(searcher,
        Seq(QWord("I"), QWord("hate"), QWord("mango")),
        2, 2,
        Seq(CaptureSpan("1", 0, 1), CaptureSpan("2", 1, 3), CaptureSpan("3", 0, 3)))
    }
  }

  it should "capture query-token spans" in {
    assertResult(Seq(
      Seq("I", "I", "like", "mango"), // No Misses
      Seq("I", "I", "hate", "MISS: those") // Miss 'those'
    )) {
      getFuzzySeqCaptureGroups(
        searcher,
        Seq(
          QWord("I"),
          QDisj(Seq(QWord("hate"), QWord("like"))),
          QDisj(Seq(QWord("mango")))
        ), 2, 3, Seq(CaptureSpan("1", 0, 1)), captureEdits = true
      )
    }
  }
}
