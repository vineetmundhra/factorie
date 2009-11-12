package cc.factorie

import scala.reflect.Manifest
import scala.collection.mutable.HashSet
import cc.factorie.util.Implicits._

// A collection abstract Variables and factor Templates for generative models (directed Bayesian networks, 
// as opposed to undirected in which there is not a DAG-shaped generative storyline).


/** An immutable-valued Variable that has a 'source' GenerativeDistribution and a 'pr' probability of being generated.  
 * For the corresponding mutable-valued Variable, see GenerativeVariable. */
trait GenerativeObservation[This<:GenerativeObservation[This] with Variable] extends Variable {
  this: This =>
  // This types are a bit verbose.  Could Scala be changed to replace them with this#type ??? 
  // Geoffrey Washburn says yes, but that Martin is simply against adding this feature to the language.
  type SourceType <: GenerativeDistribution[This]
  protected var _source: SourceType = _ // TODO Consider making this 'private' instead of 'protected'
  @inline final def source = _source
  def setSource(s:SourceType)(implicit d:DiffList) : Unit = {
    if (_source != null) _source._unregisterSample(this)
    _setSource(s)
    if (_source != null) _source._registerSample(this)
  }
  /** Set 'source' directly without coordinating via source.(un)generate.  Don't call this yourself; call 'setSource' instead. */
  @inline override def _setSource(s:AnyRef)(implicit d:DiffList) : Unit = {
    val ss = s.asInstanceOf[SourceType] // TODO Arg.  I want s:SourceType, but Scala type system doesn't like M#OutcomeType vs M.OutcomeType
    if (d != null) d += SetSourceDiff(_source, ss)
    _source = ss
  }
  /** Register this variable as having been generated from source 's'. */
  def ~(s:SourceType): this.type = { setSource(s)(null); this }
  /** Register this variable as having been generated from the source indicated by the MixtureChoice 'mmc'. */
  def ~[M<:SourceType](mmc:MixtureChoice[M,_]) : this.type = {
    mmc.setOutcome(this); 
    this.~(mmc.choice) // either here or in mmc.setOutcome; not sure which is more natural
  }
  /** Probability of this variable given its 'source' parents. */
  def pr: Double = _source.pr(this)
  /** log-probability of this variable given its 'source' parents. */
  def logpr: Double = Math.log(pr)
  case class SetSourceDiff(oldSource:SourceType, newSource:SourceType) extends Diff {
    def variable = GenerativeObservation.this
    def redo = _source = newSource
    def undo = _source = oldSource
  }
}

trait GenerativeVariable[This<:GenerativeVariable[This] with Variable] extends GenerativeObservation[This] with AbstractGenerativeVariable {
  this: This =>
  /** Register this variable as having been generated from source 's', and furthermore set this variable's value to a sample from 's'. */
  def :~(s:SourceType): this.type = { s.sampleInto(this); this.~(s); this } 
  // Better to sampleInto first then ~, because ~ may look at this.value and because ~ may add this' currentValue to s' parameter estimate
  // Note however that you can't do "MixtureChoice :~ Multinomial" because of outcome-setting-ordering.
  // We could try to change the design to allow this if we decide it is important.
  /** Register this variable as having been generated from the MixtureComponent source indicated by the given MixtureChoice, 
      and furthermore set this variable's value to a sample from it. */
  def :~[M<:SourceType](mmc:MixtureChoice[M,_]) : this.type = { mmc.choice.sampleInto(this); this.~(mmc); this }
  //def maximize(implicit d:DiffList): Unit // TODO Consider adding this also; how interact with 'estimate'?  // Set value to that with highest probability
}

/** A stand-in for GenerativeVariable that does not take type parameters, which we use as the type argument to GenerativeVariableSampler. */
trait AbstractGenerativeVariable extends Variable {
  /** Set this variable to a new value, sampled according to the distribution 
  		indicated by both its 'source' parents and also its 'generatedSamples' children if available. */
	def sample(implicit d:DiffList): Unit
}

// TODO  Consider something like this.  A trait for factory objects
//trait GenerativeFactory[Source<:AbstractGenerativeDistribution] { def apply(Source#OutcomeType#ValueType): Source#OutcomeType }

/** A Variable representing a probability distribution that generates other variable values with type OutcomeType
    It provides methods returning the probability of given values, and for (re-)sampling a new value into an existing variable. 
    Optionally, it keeps track of all the variables generated, for use in estimating the parameters of this distribution,
    and for use in finding neighbors for factor Templates. */
// Change this to O<:TypedVariable so that we can add def sampleValue:O#ValueType
trait GenerativeDistribution[O<:Variable] extends AbstractGenerativeDistribution {
  // Note that 'O' is not *required* to be a GenerativeVariable.  This allows us to put SingleIndexedVariable into Multinomial, for example.
  type OutcomeType = O // TODO Consider insisting the OutcomeType = GenerativeObservation[O]; For now I've simly added a noop 'setSource' method to Variable
}

/** A stand-in for GenerativeDistribution that does not take type parameters */
trait AbstractGenerativeDistribution extends Variable {
  type OutcomeType <: Variable 
  // Perhaps we really want OutcomeType to be 'either ConstantVariable or GenerativeObservation', 
  //  because changing Variables should be able to find their source.
  // How to do this?  
  // Perhaps define a trait VariableWithSource[This] { type SourceType <: GenerativeDistribution[This]; def _setSource...}
  // and then make ConstantVariable and GenerativeObservation both inherit from it?
  // No, I don't think this is necessary.  Putting Real into Gaussian would be useful, 
  //  and it is useful even when we are not trying to track score changes due to changes in Real.value
  type VariableType <: AbstractGenerativeDistribution
	def estimate: Unit // TODO consider removing this.  Paramter estimation for generative models should be seen as inference?
  lazy val generatedSamples = new HashSet[OutcomeType];
  var keepGeneratedSamples = true
  def _registerSample(o:OutcomeType)(implicit d:DiffList): Unit = if (keepGeneratedSamples) {
    if (generatedSamples.contains(o)) throw new Error("Already generated outcome "+o) 
    generatedSamples += o
    if (d != null) d += GenerativeDistributionRegisterDiff(o)
  }
  def _unregisterSample(o:OutcomeType)(implicit d:DiffList): Unit = if (keepGeneratedSamples) {
    generatedSamples -= o
    if (d != null) d += GenerativeDistributionUnregisterDiff(o)
  }
  /* Consider something like registerWeightedSample(o,w), for EM.  
   * But then what is o.source?  A 'WeightedSource' collection of sources?  
   * Should this be done by some more general variational approach? 
  lazy val weightedGeneratedSamples = new HashMap[OutcomeType,Double];
  // TODO Candidate for Scala 2.8 @specialized
  def _registerWeightedSample(o:OutcomeType, weight:Double)(implicit d:DiffList): Unit = if (keepGeneratedSamples) {
    if (weightedGeneratedSamples.contains(o)) throw new Error("Already generated outcome "+o) 
    weightedGeneratedSamples(o) = weight
    if (d != null) d += GenerativeDistributionRegisterWeightedDiff(o, weight)
  }
  def _unregisterWeightedSample(o:OutcomeType, weight:Double)(implicit d:DiffList): Unit = if (keepGeneratedSamples) {
    weightedGeneratedSamples -= o
    if (d != null) d += GenerativeDistributionUnregisterWeightedDiff(o, weight)
  }*/
  /** Notify this GenerativeDistribution that it is now associated with an additional sampled outcome, and set o's source to this. */
  final def registerSample(o:OutcomeType)(implicit d:DiffList): Unit = {
    _registerSample(o)
		//o._setSource(this)//.asInstanceOf[OutcomeType#SourceType]
    o._setSource(this)
  }
  /** Notify this GenerativeDistribution that it is no longer associated with a sampled outcome, and set o's source to null. */
  final def unregisterSample(o:OutcomeType)(implicit d:DiffList): Unit = {
    _unregisterSample(o)
  	//if (o.isInstanceOf[GenerativeObservation[_]]) o.asInstanceOf[GenerativeObservation[_]]._setSource(null.asInstanceOf[OutcomeType#SourceType])
  	o._setSource(null)
  }
  // TODO consider removing preChange/postChange, because it requires extra infrastructure/Diffs from implementers?  Can just use (un)registerSample
  /** Notify this GenerativeDistribution that the value of its associated outcome 'o' is about to change.  
      Calls to this method are always paired with (followed by) a call to postChange. */
  def preChange(o:OutcomeType)(implicit d:DiffList): Unit = if (!generatedSamples.contains(o)) throw new Error("Outcome not present")
  def postChange(o:OutcomeType)(implicit d:DiffList): Unit = if (!generatedSamples.contains(o)) throw new Error("Outcome not present")
  /** Return the probability that this GenerativeDistribution would generate outcome 'o' */
  def pr(o:OutcomeType): Double
  /** Return the log-probability that this GenerativeDistribution would generate outcome 'o' */
	def logpr(o:OutcomeType): Double = Math.log(pr(o))
	/** Change the value of outcome 'o' to a value sampled from this GenerativeDistribution */
	def sampleInto(o:OutcomeType): Unit // TODO Perhaps this should take a DiffList; then GenerativeVariable.sample could be implemented using it
	// Fighting with the Scala type system, see:
	// http://www.nabble.com/Path-dependent-type-question-td16767728.html
  // http://www.nabble.com/Fwd:--lift--Lift-with-Scala-2.6.1--td14571698.html 
	def unsafeRegisterSample(o:Variable)(implicit d:DiffList) = registerSample(o.asInstanceOf[OutcomeType])
	def unsafeUnregisterSample(o:Variable)(implicit d:DiffList) = unregisterSample(o.asInstanceOf[OutcomeType])
	def unsafePr(o:Variable) = pr(o.asInstanceOf[OutcomeType])
	def unsafeLogpr(o:Variable) = logpr(o.asInstanceOf[OutcomeType])
	case class GenerativeDistributionRegisterDiff(m:OutcomeType) extends Diff {
		def variable = AbstractGenerativeDistribution.this.asInstanceOf[VariableType]
		def redo = { if (generatedSamples.contains(m)) throw new Error else generatedSamples += m}
		def undo = { generatedSamples -= m }
	}
 	case class GenerativeDistributionUnregisterDiff(m:OutcomeType) extends Diff {
		def variable = AbstractGenerativeDistribution.this.asInstanceOf[VariableType]
		def redo = { generatedSamples -= m }
		def undo = { if (generatedSamples.contains(m)) throw new Error else generatedSamples += m}
	}
}




  
/** Trait for any distribution that might be selected as as part of a Multinomial mixture.
    Since it inherits from ItemizedVariable, the variables themselves are entered into the Domain,
    and this.index is a densely-packed numbering of the variables.
    The number of components in the mixture is the size of the domain.  Values of the domain are these MixtureComponent objects.
    Note that this is not a MultinomialOutcome, it is the *value* of MultinomialOutcome. */
trait MixtureComponent[This<:MixtureComponent[This] with AbstractGenerativeDistribution with ItemizedVariable[This]] extends AbstractGenerativeDistribution with ItemizedVariable[This] {
  this : This =>
}


// Example usage, in LDA: 
// class Topic extends Multinomial[Word] with MixtureComponent[Topic]
// class Z extends MixtureChoice[Topic,Z]; Domain.alias[Z,Topic]
// class Theta extends Multinomial[Z];
/** A multinomial outcome that is an indicator of which mixture component in a MixtureChoice is chosen.  
    The "Z" in Latent Dirichlet Allocation is an example. */                                 
class MixtureChoice[M<:MixtureComponent[M],This<:MixtureChoice[M,This]](implicit mm:Manifest[M], mt:Manifest[This]) extends MultinomialOutcomeVariable[This] {
  this : This =>
  type VariableType = This
  type ValueType = M
  class DomainInSubclasses
  def choice: M = domain.get(index)
  _index = Global.random.nextInt(domain.size) // TODO is this how _index should be initialized?
  if (!Global.defaultModel.contains(MixtureChoiceTemplate)) Global.defaultModel += MixtureChoiceTemplate
  // The following is done in MultinomialOutcome[This]:  type DomainType = IndexedDomain[M]; class DomainClass extends IndexedDomain[M]
  Domain.alias[This,M](mt,mm)
	private var _outcome : M#OutcomeType = _
	@inline final def outcome : M#OutcomeType = _outcome // The particular outcome that was generated from this choice of mixture component
	def setOutcome(o:M#OutcomeType) = if (_outcome == null) _outcome = o else throw new Error("Outcome already set")
	override def setByIndex(newIndex:Int)(implicit d:DiffList) = {
	  if (_outcome == null) throw new Error("No outcome yet set.")
	  choice.unsafeUnregisterSample(outcome)
	  super.setByIndex(newIndex) // this changes the value of 'choice'
	  choice.unsafeRegisterSample(outcome)
	}
  // example.LDA on 127 documents with 185129 tokens and 17032 types (Users/mccallum/research/data/text/nipstxt/nips05)
  // 9 iterations, printing topics 4 times: 
  // this.sample with this.setByIndex = 118.4 seconds (old version)
  // this.sample with local super.setByIndex = 114.5 seconds
  // this.sample with above and "val dom" = 109.0 seconds
  // this.sample with above and "DirichletMultinomial.pre/postChange" = 108.0 seconds // TODO Didn't help much; consider removing pre/postChange?
  // NOT DEFAULT this.sample with above and keepGeneratedSamples = false = 103.3 seconds // in DirichletMultinomial?
  // NOT DEFAULT this.sample with above and noDiffList = 503.0 seconds.  Fishy!!!!  // TODO Why?????  Investigate!
  // this.sample with above, after Generative infrastructure overhaul = 115.4 seconds
  // this.sample with above, and _setByIndex instead of setByIndex = 111.4 seconds
  // GibbsSampler = 368.3 seconds
  override def sample(implicit d:DiffList): Unit = {
    //println("MixtureChoice.sample "+index+" diff "+d)
    val src = source
    // Remove this variable and its sufficient statistics from the model
    choice.unsafeUnregisterSample(outcome)
    source.preChange(this) // TODO this could be preChange(this) instead of unregisterSample(this)
    val dom = domain // Avoid 'domain' HashMap lookup in inner loop
    val distribution = Array.fromFunction[Double]((i:Int) => src.pr(i) * dom.get(i).unsafePr(outcome))(dom.size)
    val i = Maths.nextDiscrete(distribution, distribution.foldLeft(0.0)(_+_))(Global.random)
    //var i = 0; val max = distribution.size; val r = Global.random.nextDouble * distribution.foldLeft(0.0)(_+_); var s = 0.0
    //while (s < r && i < max) { s += distribution(i); i += 1 }
    //choice.unsafeGenerate(outcome) // Put outcome back, although, inefficiently, the next line moves it again
    //setByIndex(i - 1) // So, instead we do it ourselves.  But then subclassers cannot meaningfully override setByIndex!! // TODO Consider alternatives
    this._setByIndex(i) // change the value of choice
    // Add the variable back into the model, with its new value
    src.postChange(this) // Could be postChange(this) instead of registerSample(this)
    choice.unsafeRegisterSample(outcome)
  }
}

  


/** A Template for scoring changes to a MixtureChoice. */ 
object MixtureChoiceTemplate extends TemplateWithStatistics1[MixtureChoice[GenericMixtureComponent,GenericMixtureChoice]] {
  def score(s:Stat) = { val mc = s.s1; mc.logpr + mc.choice.unsafeLogpr(mc.outcome) } 
  // MixtureComponent.logpr current includes both source and outcome, but perhaps it shouldn't and both should be here
}
abstract class GenericMultinomialOutcome extends MultinomialOutcome[GenericMultinomialOutcome] { def index = -1 }
// The "2" below is arbitrary, but since this constructor is never called, it shouldn't make any difference
abstract class GenericMixtureComponent extends DenseCountsMultinomial[GenericMultinomialOutcome](2) with MixtureComponent[GenericMixtureComponent]
abstract class GenericMixtureChoice extends MixtureChoice[GenericMixtureComponent,GenericMixtureChoice]



//trait GenericGenerativeVariable extends GenerativeVariable[GenericGenerativeVariable]
class GenerativeVariableSampler extends Sampler[AbstractGenerativeVariable] {
  def process1(v:AbstractGenerativeVariable): DiffList = { val d = newDiffList; v.sample(d); d }
}
