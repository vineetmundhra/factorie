/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.app.nlp
import cc.factorie._
import scala.collection.mutable.ArrayBuffer

trait DocumentSubstring {
  def document: Document
  def stringStart: Int
  def stringEnd: Int
  def string: String
}

trait Section extends ChainWithSpansVar[Section,TokenSpan,Token] with DocumentSubstring with Attr {
  def string: String = document.string.substring(stringStart, stringEnd)
  
  /** Just a clearly-named alias for Chain.links. */
  def tokens: IndexedSeq[Token] = links
  
  // Managing Sentences
  private var _sentences = new ArrayBuffer[Sentence]
  def sentences: Seq[Sentence] = _sentences
  def hasSentences: Boolean = _sentences.length > 0
  // potentially very slow for large documents. // TODO Why isn't this simply using token.sentence??  Or even better, just remove this method. -akm
  //def sentenceContaining(token: Token): Sentence = sentences.find(_.contains(token)).getOrElse(null)

  // Managing Spans, keeping Sentence-spans separate from all other TokenSpans
  override def +=(s:TokenSpan): Unit = s match {
    case s:Sentence => {
      if (_sentences.length == 0 || _sentences.last.end < s.start) _sentences += s
      else throw new Error("Sentences must be added in order and not overlap.")
      s._chain = this // not already done in += be cause += is not on ChainWithSpans
    }
    case s:TokenSpan => super.+=(s)
  }
  override def -=(s:TokenSpan): Unit = s match {
    case s:Sentence => _sentences -= s
    case s:TokenSpan => super.-=(s)
  }
}

/** Concrete implementation */
class BasicSection(val document:Document, val stringStart:Int, val stringEnd:Int) extends Section


class Document extends DocumentSubstring with Attr {
  def this(stringContents:String) = { this(); _string = stringContents }
  private var _name: String = null
  def name: String = _name
  def setName(s:String): this.type = { _name = s; this }
  
  // One of the following two is always null, the other non-null
  private var _string: String = ""
  private var _stringbuf: StringBuffer = null
  /** Append the string 's' to this Document.
      @return the length of the Document's string before string 's' was appended. */
  def appendString(s:String): Int = {
    if (_stringbuf eq null) _stringbuf = new StringBuffer(_string)
    val result = _stringbuf.length
    _stringbuf.append(s)
    _string = null
    result
  }
  def string: String = {
    this.synchronized {
      if (_string eq null) _string = _stringbuf.toString
      _stringbuf = null
    }
    _string
  }
  def stringLength: Int = if (_string ne null) _string.length else _stringbuf.length

  // For DocumentSubstring
  def document: Document = this
  def stringStart: Int = 0
  def stringEnd: Int = stringLength
  
  // Managing sections.  These are the canonical Sections, but alternative Sections can be attached as Attr's.
  val asSection: Section = new Section { def document: Document = Document.this; def stringStart = 0; def stringEnd = document.stringEnd }
  private var _sections: Seq[Section] = List(asSection)
  def sections: Seq[Section] = _sections //if (_sections.length == 0) return Seq(this) else _sections
  
  // A few iterators that combine the results from the Sections
  //def tokens: Iterator[Token] = for (section <- sections.iterator; token <- section.tokens.iterator) yield token
  def tokens: Iterable[Token] = if (sections.length == 1) sections.head.tokens else new Iterable[Token] { def iterator = for (section <- sections.iterator; token <- section.tokens.iterator) yield token }
  //def sentences: Iterator[Sentence] = for (section <- sections.iterator; sentence <- section.sentences.iterator) yield sentence
  def sentences: Iterable[Sentence] = if (sections.length == 1) sections.head.sentences else new Iterable[Sentence] { def iterator = for (section <- sections.iterator; sentence <- section.sentences.iterator) yield sentence } 
  //def spans: Iterator[TokenSpan] = for (section <- sections.iterator; span <- section.spans.iterator) yield span
  def spans: Iterable[TokenSpan] = if (sections.length == 1) sections.head.spans else new Iterable[TokenSpan] { def iterator = for (section <- sections.iterator; span <- section.spans.iterator) yield span }
  
  def tokenCount: Int = if (sections.length == 0) sections.head.length else sections.foldLeft(0)((result, section) => result + section.length)
  def sentenceCount: Int = if (sections.length == 0) sections.head.sentences.length else sections.foldLeft(0)((result, section) => result + section.sentences.length)
  def spanCount: Int = if (sections.length == 0) sections.head.spans.length else sections.foldLeft(0)((result, section) => result + section.spans.length)
    
  /** Keeping records of which DocumentAnnotators have been run on this document, producing which annotations.
      The collection of DocumentAnnotators that have been run on this Document.  
      A Map from the annotation class to the DocumentAnnotator that produced it.
      Note that this map records annotations placed not just on the Document itself, but also its constituents,
      such as TokenSpan, Token, Sentence, etc. */
  val annotators = new DocumentAnnotatorMap
  /** Has an annotation of class 'c' been placed somewhere within this Document? */
  def hasAnnotation(c:Class[_]): Boolean = annotators.keys.exists(k => c.isAssignableFrom(k))
  /** Which DocumentAnnotator produced the annotation of class 'c' within this Document.  If  */
  def annotatorFor(c:Class[_]): Option[DocumentAnnotator] = annotators.keys.find(k => c.isAssignableFrom(k)).collect({case k:Class[_] => annotators(k)})
  
  /** Return a String containing the token strings in the document, with sentence and span boundaries indicated with SGML. */
  def sgmlString: String = {
    val buf = new StringBuffer
    for (section <- sections; token <- section.tokens) {
      if (token.isSentenceStart) buf.append("<sentence>")
      token.startsSpans.foreach(span => buf.append("<"+span.name+">"))
      buf.append(token.string)
      token.endsSpans.foreach(span => buf.append("</"+span.name+">"))
      if (token.isSentenceEnd) buf.append("</sentence>")
      buf.append(" ")
    }
    buf.toString
  }
  
  /** Return a String containing the token strings in the document, with one-word-per-line 
      and various tab-separated attributes appended on each line. */
  // TODO Remove this default argument -akm
  def owplString(attributes:Iterable[(Token)=>Any] = List((t:Token) => t.posLabel.categoryValue)): String = {
    val buf = new StringBuffer
    for (section <- sections; token <- section.tokens) {
      if (token.isSentenceStart) buf.append("\n")
      buf.append("%d\t%d\t%s\t".format(token.position+1, token.positionInSentence+1, token.string))
      //buf.append(token.stringStart); buf.append("\t")
      //buf.append(token.stringEnd)
      for (af <- attributes) {
        buf.append("\t")
        af(token) match {
          case cv:CategoricalVar[_,String @unchecked] => buf.append(cv.categoryValue.toString)
          case null => {}
          case v:Any => buf.append(v.toString)
        }
      }
      buf.append("\n")
    }
    buf.toString
  }

}




///** Value is the sequence of tokens */
//class OldDocument extends ChainWithSpansVar[Document,TokenSpan,Token] with DocumentSubstring with Attr {
//  def this(stringContents:String) = { this(); _string = stringContents }
//  private var _name: String = null
//  def name: String = _name
//  def setName(s:String): this.type = { _name = s; this }
//  
//  // One of the following two is always null, the other non-null
//  private var _string: String = ""
//  private var _stringbuf: StringBuffer = null
//  /** Append the string 's' to this Document.
//      @return the length of the Document's string before string 's' was appended. */
//  def appendString(s:String): Int = {
//    if (_stringbuf eq null) _stringbuf = new StringBuffer(_string)
//    val result = _stringbuf.length
//    _stringbuf.append(s)
//    _string = null
//    result
//  }
//  def string: String = {
//    this.synchronized {
//      if (_string eq null) _string = _stringbuf.toString
//      _stringbuf = null
//    }
//    _string
//  }
//  def stringLength: Int = if (_string ne null) _string.length else _stringbuf.length
//  
//  // For DocumentSubstring
//  def document: Document = this
//  def stringStart: Int = 0
//  def stringEnd: Int = stringLength
//  
//  /** Just a clearly-named alias for Chain.links. */
//  def tokens: IndexedSeq[Token] = links
//  
//  // Managing Sentences
//  private var _sentences = new ArrayBuffer[Sentence]
//  def sentences: Seq[Sentence] = _sentences
//  def hasSentences: Boolean = _sentences.length > 0
//  // potentially very slow for large documents. // TODO Why isn't this simply using token.sentence??  Or even better, just remove this method. -akm
//  //def sentenceContaining(token: Token): Sentence = sentences.find(_.contains(token)).getOrElse(null)
//
//  // Managing Spans, keeping Sentence-spans separate from all other TokenSpans
//  override def +=(s:TokenSpan): Unit = s match {
//    case s:Sentence => {
//      if (_sentences.length == 0 || _sentences.last.end < s.start) _sentences += s
//      else throw new Error("Sentences must be added in order and not overlap.")
//      s._chain = this // not already done in += be cause += is not on ChainWithSpans
//    }
//    case s:TokenSpan => super.+=(s)
//  }
//  override def -=(s:TokenSpan): Unit = s match {
//    case s:Sentence => _sentences -= s
//    case s:TokenSpan => super.-=(s)
//  }
//  
//  /** Keeping records of which DocumentAnnotators have been run on this document, producing which annotations.
//      The collection of DocumentAnnotators that have been run on this Document.  
//      A Map from the annotation class to the DocumentAnnotator that produced it.
//      Note that this map records annotations placed not just on the Document itself, but also its constituents,
//      such as TokenSpan, Token, Sentence, etc. */
//  val annotators = new DocumentAnnotatorMap
//  /** Has an annotation of class 'c' been placed somewhere within this Document? */
//  def hasAnnotation(c:Class[_]): Boolean = annotators.keys.exists(k => c.isAssignableFrom(k))
//  /** Which DocumentAnnotator produced the annotation of class 'c' within this Document.  If  */
//  def annotatorFor(c:Class[_]): Option[DocumentAnnotator] = annotators.keys.find(k => c.isAssignableFrom(k)).collect({case k:Class[_] => annotators(k)})
//  
//  /** Return a String containing the token strings in the document, with sentence and span boundaries indicated with SGML. */
//  def sgmlString: String = {
//    val buf = new StringBuffer
//    for (token <- tokens) {
//      if (token.isSentenceStart) buf.append("<sentence>")
//      token.startsSpans.foreach(span => buf.append("<"+span.name+">"))
//      buf.append(token.string)
//      token.endsSpans.foreach(span => buf.append("</"+span.name+">"))
//      if (token.isSentenceEnd) buf.append("</sentence>")
//      buf.append(" ")
//    }
//    buf.toString
//  }
//  
//  /** Return a String containing the token strings in the document, with one-word-per-line 
//      and various tab-separated attributes appended on each line. */
//  // TODO Remove this default argument -akm
//  def owplString(attributes:Iterable[(Token)=>Any] = List((t:Token) => t.posLabel.categoryValue)): String = {
//    val buf = new StringBuffer
//    for (token <- tokens) {
//      if (token.isSentenceStart) buf.append("\n")
//      buf.append("%d\t%d\t%s\t".format(token.position+1, token.positionInSentence+1, token.string))
//      //buf.append(token.stringStart); buf.append("\t")
//      //buf.append(token.stringEnd)
//      for (af <- attributes) {
//        buf.append("\t")
//        af(token) match {
//          case cv:CategoricalVar[_,String] => buf.append(cv.categoryValue.toString)
//          case null => {}
//          case v:Any => buf.append(v.toString)
//        }
//      }
//      buf.append("\n")
//    }
//    buf.toString
//  }
//}

/** A Cubbie for serializing a Document, with separate slots for the Tokens, Sentences, and TokenSpans. 
    Note that it does not yet serialize Sections, and relies on Document.asSection being the only Section. */
class DocumentCubbie[TC<:TokenCubbie,SC<:SentenceCubbie,TSC<:TokenSpanCubbie](val tc:()=>TC, val sc:()=>SC, val tsc:()=>TSC) extends Cubbie with AttrCubbieSlots {
  val name = StringSlot("name")
  val string = StringSlot("string")  
  val tokens = CubbieListSlot("tokens", tc)
  val sentences = CubbieListSlot("sentences", sc)
  val spans = CubbieListSlot("spans", tsc)
  def storeDocument(doc:Document): this.type = {
    name := doc.name
    string := doc.string
    if (doc.asSection.length > 0) tokens := doc.tokens.toSeq.map(t => tokens.constructor().storeToken(t))
//    if (doc.spans.length > 0) spans := doc.spans.map(s => spans.constructor().store(s))
    if (doc.asSection.sentences.length > 0) sentences := doc.sentences.toSeq.map(s => sentences.constructor().storeSentence(s))
    storeAttr(doc)
    this
  }
  def fetchDocument: Document = {
    val doc = new Document(string.value).setName(name.value)
    if (tokens.value ne null) tokens.value.foreach(tc => doc.asSection += tc.fetchToken)
    //if (spans.value ne null) spans.value.foreach(sc => doc += sc.fetch(doc))
    if (sentences.value ne null) sentences.value.foreach(sc =>  sc.fetchSentence(doc.asSection))
    fetchAttr(doc)
    doc
  }
}

// TODO Consider moving this to file util/Attr.scala
trait AttrCubbieSlots extends Cubbie {
  val storeHooks = new cc.factorie.util.Hooks1[Attr]
  val fetchHooks = new cc.factorie.util.Hooks1[AnyRef]
  def storeAttr(a:Attr): this.type = { storeHooks(a); this }
  def fetchAttr(a:Attr): Attr = { fetchHooks(a); a }
}

trait DateAttrCubbieSlot extends AttrCubbieSlots {
  val date = DateSlot("date")
  storeHooks += ((a:Attr) => date := a.attr[java.util.Date])
  //fetchHooks += ((a:Attr) => a.attr += date.value)
  fetchHooks += { case a:Attr => a.attr += date.value }
}


