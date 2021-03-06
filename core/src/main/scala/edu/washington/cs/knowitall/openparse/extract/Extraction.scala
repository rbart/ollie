package edu.washington.cs.knowitall.openparse.extract

import scala.collection.{SortedSet, Set}

import edu.washington.cs.knowitall.collection.immutable.graph.pattern.Match
import edu.washington.cs.knowitall.collection.immutable.graph.{Direction, Graph, DirectedEdge}
import edu.washington.cs.knowitall.collection.immutable.Interval
import edu.washington.cs.knowitall.openparse.GraphExpansions.{expand, components, augment}
import edu.washington.cs.knowitall.openparse.OpenParse
import edu.washington.cs.knowitall.tool.parse.graph.{DependencyPattern, DependencyNode, DependencyGraph}
import edu.washington.cs.knowitall.tool.stem.MorphaStemmer.instance
import edu.washington.cs.knowitall.tool.stem.Stemmer

import Extraction.{Part, ClausalComponent, AdverbialModifier}

/** A representation of an OpenParse extraction.
  * 
  * @author Michael Schmitz
  */
abstract class Extraction(val relLemmas: Set[String]) {
  def arg1Text: String
  def relText: String
  def arg2Text: String

  def this(relText: String) = this(relText.split(" ").map(implicitly[Stemmer].lemmatize(_)).toSet -- OpenParse.LEMMA_BLACKLIST)

  override def equals(that: Any) = that match {
    case that: Extraction => (that canEqual this) && that.arg1Text == this.arg1Text && that.relText == this.relText && that.arg2Text == this.arg2Text
    case _ => false
  }
  def canEqual(that: Any) = that.isInstanceOf[Extraction]
  override def hashCode = arg1Text.hashCode + 39 * (relText.hashCode + 39 * arg2Text.hashCode)

  override def toString() = Iterable(arg1Text, relText, arg2Text).mkString("(", "; ", ")")

  def softMatch(that: Extraction) =
    (that.arg1Text.contains(this.arg1Text) || this.arg1Text.contains(that.arg1Text)) &&
      this.relLemmas == that.relLemmas &&
      (that.arg2Text.contains(this.arg2Text) || this.arg2Text.contains(that.arg2Text))
}

class SimpleExtraction(
  override val arg1Text: String,
  override val relText: String,
  relLemmas: Set[String],
  override val arg2Text: String)
  extends Extraction(relLemmas) {

  def this(arg1Text: String, relText: String, arg2Text: String) = this(arg1Text,
    relText,
    relText.split(" ").map(implicitly[Stemmer].lemmatize(_)).toSet -- OpenParse.LEMMA_BLACKLIST,
    arg2Text)

  def replaceRelation(relation: String) =
    new SimpleExtraction(this.arg1Text, this.relText, this.relLemmas, this.arg2Text)
}

/** A more informative representation of an OpenParse extraction.
  * 
  * @author Michael Schmitz
  */
class DetailedExtraction(
  val extractor: PatternExtractor,
  val `match`: Match[DependencyNode],
  val arg1: Part,
  val rel: Part,
  val arg2: Part,
  val clausal: Option[ClausalComponent] = None,
  val modifier: Option[AdverbialModifier] = None)
  extends Extraction(rel.text) {

  override def arg1Text = arg1.text
  override def relText = rel.text
  override def arg2Text = arg2.text

  def this(extractor: PatternExtractor, mch: Match[DependencyNode],
    arg1Nodes: SortedSet[DependencyNode],
    relNodes: SortedSet[DependencyNode],
    arg2Nodes: SortedSet[DependencyNode]) =
    this(extractor, mch, new Part(arg1Nodes), new Part(relNodes), new Part(arg2Nodes))

  def nodes = arg1.nodes ++ rel.nodes ++ arg2.nodes
  def edges = `match`.bipath.path

  def replaceRelation(relation: String) =
    new DetailedExtraction(extractor, `match`, this.arg1, Part(this.rel.nodes, relation), this.arg2, this.clausal, this.modifier)
}

object DetailedExtraction {
  def nodesToString(nodes: Iterable[DependencyNode]) = nodes.iterator.map(_.text).mkString(" ")
}


/** Includes logic for expanding relations and arguments.
  * 
  * @author Michael Schmitz
  */
object Extraction {
  /** Representation of a part of an extraction.
    * 
    * @author Michael Schmitz
    */
  case class Part(nodes: SortedSet[DependencyNode], text: String) {
    def this(nodes: SortedSet[DependencyNode]) = {
      this(nodes, DetailedExtraction.nodesToString(nodes))
    }

    def this(nodes: Iterable[DependencyNode]) = {
      this(SortedSet[DependencyNode]() ++ nodes, DetailedExtraction.nodesToString(nodes))
    }

    def span = Interval.span(nodes.map(_.indices))
  }
  case class ClausalComponent(rel: Part, arg: Part) {
    def text = arg.text + " " + rel.text
  }
  case class AdverbialModifier(contents: Part) {
    def text = contents.text
  }

  private val attributionPattern = DependencyPattern.deserialize("{old} <ccomp< {rel} >nsubj> {arg}")
  private val conditionalPattern = DependencyPattern.deserialize("{old} <ccomp< {rel} >nsubj> {arg}")
  def fromMatch(expand: Boolean)(graph: DependencyGraph, m: Match[DependencyNode], ex: PatternExtractor): Option[DetailedExtraction] = {
    def clausalComponent(node: DependencyNode, until: Set[DependencyNode]) = {
      attributionPattern.apply(graph.graph, node) match {
        case List(m) =>
          assume(m.nodeGroups.get("rel").isDefined)
          assume(m.nodeGroups.get("arg").isDefined)

          val rel = m.nodeGroups("rel").node
          val arg = m.nodeGroups("arg").node

          val Part(expandedRelNodes, expandedRelText) = expandRelation(graph, rel, until + arg)
          val expandedArg = expandArgument(graph, arg, until + rel)

          Some(ClausalComponent(Part(expandedRelNodes, expandedRelText), Part(expandedArg, DetailedExtraction.nodesToString(expandedArg))))
        case _ => None
      }
    }

    def adverbialModifier(node: DependencyNode, until: Set[DependencyNode]): Option[AdverbialModifier] = {
      val neighbors = graph.graph.neighbors(node, dedge => dedge.dir == Direction.Down && dedge.edge.label == "advcl")
      val nodes = neighbors.flatMap(graph.graph.inferiors(_))
      if (nodes.isEmpty) None
      else {
        val span = Interval.span(nodes.map(_.indices))
        val clause = graph.nodes.filter(node => span.superset(node.indices))
        Some(AdverbialModifier(Part(clause, DetailedExtraction.nodesToString(clause))))
      }
    }

    val groups = m.nodeGroups

    val rels = groups.filter(_._1 startsWith "rel").toSeq.sortBy(_._1).map(_._2.node)
    if (rels.isEmpty) (throw new IllegalArgumentException("no rel: " + m))
    val arg1 = groups.get("arg1").map(_.node) getOrElse (throw new IllegalArgumentException("no arg1: " + m))
    val arg2 = groups.get("arg2").map(_.node) getOrElse (throw new IllegalArgumentException("no arg2: " + m))

    val expandedArg1 = if (expand) expandArgument(graph, arg1, rels.toSet) else SortedSet(arg1)
    val expandedArg2 = if (expand) expandArgument(graph, arg2, rels.toSet) else SortedSet(arg2)
    val Part(expandedRelNodes, expandedRelText) =
      if (expand) {
        val expansions = rels.map(rel => expandRelation(graph, rel, expandedArg1 ++ expandedArg2))
        Part(expansions.map(_.nodes).reduce(_ ++ _), expansions.map(_.text).mkString(" "))
      } else Part(SortedSet.empty[DependencyNode] ++ rels, rels.map(_.text).mkString(" "))

    val nodes = expandedArg1 ++ expandedArg2 ++ expandedRelNodes
    val clausal = rels.flatMap(rel => clausalComponent(rel, nodes)).headOption
    val modifier = rels.flatMap(rel => adverbialModifier(rel, nodes)).headOption

    if (Interval.span(expandedArg1.map(_.indices)(scala.collection.breakOut)) intersects Interval.span(expandedArg2.map(_.indices)(scala.collection.breakOut))) {
      // logger.debug("invalid: arguments overlap: " + DetailedExtraction.nodesToString(expandedArg1) + ", " + DetailedExtraction.nodesToString(expandedArg2))
      None
    } else Some(new DetailedExtraction(ex, m, new Part(expandedArg1), Part(expandedRelNodes, expandedRelText), new Part(expandedArg2), clausal = clausal, modifier = modifier))
  }

  private val argumentExpansionLabels = Set("det", "prep_of", "amod", "num", "number", "nn", "poss", "quantmod", "neg")
  def expandArgument(graph: DependencyGraph, node: DependencyNode, until: Set[DependencyNode]): SortedSet[DependencyNode] = {
    def expandNode(node: DependencyNode) = {
      val expansion = expand(graph, node, until, argumentExpansionLabels)
      if (expansion.exists(_.isProperNoun)) expansion
      else expansion ++ components(graph, node, Set("rcmod", "infmod", "partmod", "ref", "prepc_of"), until, false)
    }

    // expand over any conjunction/disjunction edges
    val nodes = graph.graph.connected(node, (dedge: DirectedEdge[_]) =>
      dedge.edge.label == "conj_and" || dedge.edge.label == "conj_or")

    if (nodes.size == 1) {
      // there are no conjunctive edges
      expandNode(node)
    }
    else {
      val flat = nodes.map(expandNode).flatten
      val span = Interval.span(flat.map(_.indices).toSeq)
      // take the nodes that cover all the nodes found
      graph.nodes.filter(node => span.superset(node.indices))
    }
  }

  def expandRelation(graph: DependencyGraph, node: DependencyNode, until: Set[DependencyNode]): Part = {
    // count the adjacent dobj edges.  We will only expand across
    // dobj components if there is exactly one adjacent dobj edge.
    // This edge may already be used, but in that case we won't
    // expand over it because of the until set.
    val dobjCount = graph.graph.edges(node).count(_.label == "dobj")
    val iobjCount = graph.graph.edges(node).count(_.label == "iobj")

    var attachLabels = Set[String]()
    if (dobjCount == 1) attachLabels += "dobj"
    if (iobjCount == 1) attachLabels += "iobj"

    def pred(edge: Graph.Edge[DependencyNode]) =
      // make sure we don't re-add the relation node
      edge.dest != node && (
          // attach adverbs
          edge.label == "advmod" && edge.dest.postag == "RB" ||
          edge.label == "aux" || edge.label == "cop" || edge.label == "auxpass" || edge.label == "prt")

    // expand across noun label for relational nouns
    // i.e. "He is the *best* president of the USA"
    val expandNounLabels = expand(graph, node, until, argumentExpansionLabels)

    // modifiers on copulars are stored on a different node
    // i.e. in "he *will* be the president"
    val cops = graph.graph.predecessors(node, (e: Graph.Edge[DependencyNode])=>e.label == "cop").headOption
    val expandCopLabels = cops.map(cop => augment(graph, cop, until, pred)).getOrElse(List.empty)

    val expansion = expandCopLabels ++ (expandNounLabels ::
      // make sure that we don't use a label that was
      // already captured by expandNounlabels.  This
      // can happen when a verb edges goes between two
      // noun labels.
      ((augment(graph, node, until, pred).map(_--expandNounLabels)) :+
      // add subcomponents
        SortedSet[DependencyNode]() ++ 
        components(graph, node, attachLabels, until, true)).filterNot { c =>
          // don't add empty components
          c.isEmpty ||
          // don't add components with just "who" or "whom"
          c.size == 1 && c.headOption.map(_.postag=="WP").getOrElse(false)
        })

    val sorted = expansion.sortBy(nodes => Interval.span(nodes.map(_.indices)))

    // perform a more complicated node->text transformation
    val texts = sorted.map(DetailedExtraction.nodesToString(_))
    Part(expansion.reduce(_ ++ _), texts.mkString(" "))
  }
}
