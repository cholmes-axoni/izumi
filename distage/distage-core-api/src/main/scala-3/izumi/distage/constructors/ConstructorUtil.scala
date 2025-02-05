package izumi.distage.constructors

import izumi.distage.model.definition.With
import izumi.fundamentals.platform.reflection.ReflectionUtil

import scala.annotation.{nowarn, tailrec}
import scala.quoted.{Expr, Quotes, Type}
import scala.collection.mutable
import izumi.distage.model.providers.Functoid
import izumi.distage.reflection.macros.{FunctoidMacro, FunctoidMacroHelpers, FunctoidParametersMacro, IdExtractorImpl}
import izumi.distage.model.reflection.Provider.{ProviderImpl, ProviderType}
import izumi.fundamentals.reflection.ReflectiveCall
import izumi.reflect.WeakTag

class ConstructorContext[R0, Q <: Quotes, U <: ConstructorUtil[Q]](using val rType: Type[R0], val qctx: Q)(val util: U & ConstructorUtil[qctx.type]) {
  import qctx.reflect.*

  // for importing if necessary, `import context.{R, rType}`
  type R = R0

  val resultTpe = TypeRepr.of[R].dealias.simplified
  val resultTpeTree = TypeTree.of[R]
  private val resultTpes = ReflectionUtil.intersectionMembers(resultTpe)
  val resultTpeSyms = resultTpes.map(_.typeSymbol)

  val refinementMethods = resultTpes.flatMap(util.unpackRefinement)

  val abstractMembers = {
    val abstractFields = resultTpeSyms.flatMap(
      _.fieldMembers
        .filter(
          m =>
            !m.flags.is(Flags.FieldAccessor) && !m.isLocalDummy && m.flags.is(Flags.Deferred) && !m.flags.is(Flags.Artifact) && !m.flags.is(Flags.Synthetic) && m.isValDef
        )
    )
    val abstractMethods = resultTpeSyms.flatMap(
      _.methodMembers
        .filter(m => m.flags.is(Flags.Method) && m.flags.is(Flags.Deferred) && !m.flags.is(Flags.Artifact) && !m.flags.is(Flags.Synthetic) && m.isDefDef)
    )
    (abstractFields ++ abstractMethods).distinct
  }

  val abstractMethodsWithParams = abstractMembers.filter(m => m.flags.is(Flags.Method) && m.paramSymss.nonEmpty)
//    val refinementMethodsWithParams = refinementMethods.filter(_._2.paramTypes.nonEmpty)

  lazy val parentTypesParameterized = {
    resultTpes
      .flatMap(
        resTpe => {
          util
            .findRequiredImplParents(resTpe.typeSymbol, resTpe)
            .map(resTpe.baseType(_))
        }
      ).distinct
  }
  lazy val constructorParamLists = parentTypesParameterized.map(t => t -> util.extractConstructorParamLists(t))
  lazy val flatCtorParams = constructorParamLists.flatMap(_._2.iterator.flatten)

  lazy val methodDecls = {
    val allMembers = abstractMembers.map(m => util.MemberRepr(m.name, m.flags.is(Flags.Method), Some(m), resultTpe.memberType(m), false)) ++ refinementMethods
    util
      .processOverrides(allMembers)
      .sortBy(_.name) // sort alphabetically because Dotty order is undefined (does not return in definition order)
  }

  def isWireableTrait: Boolean = abstractMethodsWithParams.isEmpty && !resultTpeSyms.exists(_.flags.is(Flags.Sealed))

  def isFactoryOrTrait: Boolean = abstractMembers.nonEmpty || refinementMethods.nonEmpty

  def implementTraitAutoImplBody(
    lamSym: Symbol,
    lamOnlyCtorArguments: List[Term],
    lamOnlyMethodArguments: List[Term],
  ): Typed = {
    val parents = util.buildParentConstructorCallTerms(constructorParamLists, lamOnlyCtorArguments)

    val name: String = s"${resultTpeSyms.map(_.name).mkString("With")}TraitAutoImpl"
    val clsSym = {
//    // Symbol.newClass(lamSym, name, parents = parentTypesParameterized, decls = methodDecls.generateDeclSymbols, selfType = None)
      ReflectiveCall.call[Symbol](Symbol, "newClass", lamSym, name, parentTypesParameterized, methodDecls.generateDeclSymbols, None)
    }

    val defs = methodDecls.zip(lamOnlyMethodArguments).map {
      case (util.MemberRepr(name, isMethod, _, _, _), arg) =>
        val methodSyms = if (isMethod) clsSym.declaredMethod(name) else List(clsSym.declaredField(name))
        assert(methodSyms.size == 1, "BUG: duplicated methods!")
        val methodSym = methodSyms.head
        if (isMethod) {
          DefDef(methodSym, _ => Some(arg))
        } else {
          ValDef(methodSym, Some(arg))
        }
    }

    val clsDef = {
      // ClassDef(clsSym, parents.toList, body = defs)
      ReflectiveCall.call[ClassDef](ClassDef, "apply", clsSym, parents.toList, defs)
    }
    val applyNewTree = Typed(Apply(Select(New(TypeIdent(clsSym)), clsSym.primaryConstructor), Nil), resultTpeTree)
    val traitCtorTree = '{ TraitConstructor.wrapInitialization[R](${ applyNewTree.asExprOf[R] })(compiletime.summonInline[WeakTag[R]]) }.asTerm
    val block = Block(List(clsDef), traitCtorTree)
    Typed(block, resultTpeTree)
  }

  def assertIsWireableTrait(isInFactoryConstructor: Boolean): Unit = {
    if (!this.isWireableTrait) {
      val tpeStr = this.resultTpeSyms.mkString(" & ")
      report.errorAndAbort(
        (if (isInFactoryConstructor) s"Factory cannot produce factories! Detected that $tpeStr is a factory, because:" else "") +
        s"""Cannot create TraitConstructor for $tpeStr: $tpeStr has abstract methods taking parameters, expected only parameterless abstract methods:
           |  ${this.abstractMethodsWithParams.map(s => s.name -> s.flags.show)}
           |[methods without parameters: ${this.abstractMembers.map(s => s.name -> s.flags.show)}]""".stripMargin
      )
    }
  }

}

class ConstructorUtil[Q <: Quotes](using val qctx: Q) { self =>
  import qctx.reflect.*

  private val withAnnotationSym: Symbol = TypeRepr.of[With].typeSymbol
  private val paramsMacro = new FunctoidParametersMacro[qctx.type](new IdExtractorImpl[qctx.type]())

  final case class ParamRepr(name: String, mbSymbol: Option[Symbol], tpe: TypeRepr)

  type ParamReprLists = List[List[ParamRepr]]

  final case class MemberRepr(name: String, isMethod: Boolean, mbSymbol: Option[Symbol], tpe: TypeRepr, isNewMethod: Boolean) {
    def generateDeclSymbol(cls: Symbol): Symbol = {
      // for () methods MethodType(Nil)(_ => Nil, _ => m.returnTpt.symbol.typeRef) instead of mtype
      val overrideFlag = if (!isNewMethod) Flags.Override else Flags.EmptyFlags
      if (isMethod) {
        Symbol.newMethod(cls, name, tpe, Flags.Method | overrideFlag, Symbol.noSymbol)
      } else {
        Symbol.newVal(cls, name, returnTypeOfMethodOrByName(tpe), overrideFlag, Symbol.noSymbol)
      }
    }
  }
  object MemberRepr {
    extension (methodDecls: List[MemberRepr]) def generateDeclSymbols(cls: Symbol): List[Symbol] = methodDecls.map(_.generateDeclSymbol(cls))
  }

  def assertSignatureIsAcceptableForFactory(signatureParams: List[ParamRepr], resultTpe: TypeRepr, clue: String): Unit = {
    require(signatureParams.groupMap(_.name)(_.tpe).forall(_._2.size == 1), s"BUG: duplicated arg names! in $clue for type $resultTpe")
  }

  def requireConcreteTypeConstructor(tpe: TypeRepr, macroName: String): Unit = {
    if (!ReflectionUtil.intersectionUnionMembers(tpe).forall(t => ReflectionUtil.allPartsStrong(t.typeSymbol.typeRef))) {
      val hint = tpe.dealias.show
      report.errorAndAbort(
        s"""$macroName: Can't generate constructor for ${tpe.show}:
           |Type constructor is an unresolved type parameter `$hint`.
           |Did you forget to put a $macroName context bound on the $hint, such as [$hint: $macroName]?
           |""".stripMargin
      )
    }
  }

  def makeFunctoid[R: Type](params: List[ParamRepr], argsLambda: Expr[Seq[Any] => R], providerType: Expr[ProviderType]): Expr[Functoid[R]] = {

    val paramDefs = params.map {
      case ParamRepr(n, s, t) => paramsMacro.makeParam(n, Right(t), s)
    }

    val out = '{
      new Functoid[R](
        new ProviderImpl[R](
          ${ Expr.ofList(paramDefs) },
          ${ FunctoidMacroHelpers.generateSafeType[R, Q] },
          ${ argsLambda },
          ${ providerType },
        )
      )
    }

//    report.warning(
//      s"""ConstructorUtil:fun=${argsLambda.show}
//         |funType=${argsLambda.asTerm.tpe}
//         |funSym=${argsLambda.asTerm.symbol}
//         |funTypeSym=${argsLambda.asTerm.tpe.typeSymbol}
//         |funTypeSymBases=${argsLambda.asTerm.tpe.baseClasses}
//         |params=${params.map(p => s"$p:symbol-annos(${p.mbSymbol.map(s => s -> s.annotations)})")}
//         |outputType=${Type.show[R]}
//         |rawOutputType=(${TypeRepr.of[R]})
//         |providerType=${providerType.show}
//         |produced=${out.show}""".stripMargin
//    )

    out
  }

  def wrapIntoFunctoidRawLambda[R: Type](
    params: List[ParamRepr]
  )(body: (Symbol, List[Term]) => Tree
  ): Expr[Seq[Any] => R] = {
    val mtpe = MethodType(List("args"))(
      _ => List(TypeRepr.of[Seq[Any]]),
      _ => TypeRepr.of[R],
    )
    Lambda(
      Symbol.spliceOwner,
      mtpe,
      {
        case (lamSym, (args: Term) :: Nil) =>
          val argRefs = params.iterator.zipWithIndex.map {
            case (ParamRepr(_, _, paramTpe), idx) =>
              paramTpe match {
                case ByNameUnwrappedTypeReprAsType('[t]) =>
                  '{ ${ args.asExprOf[Seq[Any]] }.apply(${ Expr(idx) }).asInstanceOf[() => t].apply() }.asTerm
                case TypeReprAsType('[t]) =>
                  '{ ${ args.asExprOf[Seq[Any]] }.apply(${ Expr(idx) }).asInstanceOf[t] }.asTerm
                case _ =>
                  report.errorAndAbort(s"Invalid higher-kinded type $paramTpe ${paramTpe.show}")
              }
          }.toList

          body(lamSym, argRefs)
      }: @nowarn("msg=match"),
    ).asExprOf[Seq[Any] => R]
  }

  def wrapCtorApplicationIntoFunctoidRawLambda[R: Type](paramss: ParamReprLists, constructorTerm: Term): Expr[Seq[Any] => R] = {
    wrapIntoFunctoidRawLambda[R](paramss.flatten) {
      (_, args0) =>
        import scala.collection.immutable.Queue
        val (_, argsLists) = paramss.foldLeft((args0, Queue.empty[List[Term]])) {
          case ((args, res), params) =>
            val (argList, restArgs) = args.splitAt(params.size)
            (restArgs, res :+ argList)
        }

        val appl = argsLists.foldLeft(constructorTerm)(_.appliedToArgs(_))
        Typed(appl, TypeTree.of[R])
    }
  }

  private object ByNameUnwrappedTypeReprAsType {
    def unapply(t: TypeRepr): Option[Type[?]] = {
      t match {
        case ByNameType(u) => Some(u.asType)
        case _ => None
      }
    }
  }

  object TypeReprAsType {
    def unapply(t: TypeRepr): Some[Type[?]] = {
      Some(t.asType)
    }
  }

  def unpackRefinement(t: TypeRepr): List[MemberRepr] = {
    t match {
      // type "meanings" taken from scala3-compiler `scala.quoted.runtime.impl.printers.SourceCode` class
      case Refinement(parent, name, methodType) =>
        methodType match {
          case _: TypeBounds =>
            // type
            unpackRefinement(parent)
          case _: ByNameType | _: MethodType | _: TypeLambda =>
            // def
            MemberRepr(name, isMethod = true, None, methodType, isNewMethod = true) :: unpackRefinement(parent)
          case _ =>
            // val
            MemberRepr(name, isMethod = false, None, methodType, isNewMethod = true) :: unpackRefinement(parent)
        }
      case _ =>
        Nil
    }
  }

  def processOverrides(memberReprs: List[MemberRepr]): List[MemberRepr] = {
    memberReprs
      .groupBy(m => (m.name, getMethodArityWithTypeParams(m.tpe)))
      .iterator.flatMap {
        case (_, members) if members.sizeIs > 1 =>
          val mostSpecificMember = members.min(Ordering.fromLessThan[TypeRepr]((t1, t2) => t1 <:< t2 && !(t1 =:= t2)).on(m => returnTypeOfByName(m.tpe)))
          val isVal = members.exists(!_.isMethod)
          List(mostSpecificMember.copy(isMethod = !isVal, isNewMethod = false))
        case (_, members) =>
          members
      }.toList
  }

  def extractMethodParamLists(methodType: TypeRepr, methodSym: Symbol): ParamReprLists = {
    def go(t: TypeRepr, paramSymss: List[List[Symbol]]): ParamReprLists = {
      t match {
        case mtpe @ MethodType(names, tpes, ret) =>
          names.iterator
            .zip(tpes)
            .zipAll(paramSymss match { case h :: _ => h; case _ => List.empty[Symbol] }, null, null.asInstanceOf[Symbol])
            .map {
              case (null, _) => null
              case ((n, t), maybeSymbol) => ParamRepr(n, Option(maybeSymbol), t)
            }
            .takeWhile(_ ne null)
            .toList :: go(ret, paramSymss.drop(1))
        case PolyType(_, _, ret) =>
          go(ret, paramSymss)
        case _ =>
          List.empty
      }
    }

    val paramSymssExcTypes = methodSym.paramSymss.filterNot(_.headOption.exists(_.isTypeParam))

    go(methodType, paramSymssExcTypes)
  }

  def getMethodArityWithTypeParams(mtpe: TypeRepr): Int = {
    mtpe match {
      case MethodType(names, _, ret) => names.size + getMethodArityWithTypeParams(ret)
      case PolyType(names, _, ret) => names.size + getMethodArityWithTypeParams(ret)
      case _ => 0
    }
  }

  @tailrec
  final def returnTypeOfMethod(t: TypeRepr): TypeRepr = {
    t match {
      case MethodType(_, _, ret) =>
        returnTypeOfMethod(ret)
      case PolyType(_, _, ret) =>
        returnTypeOfMethod(ret)
      case r =>
        r
    }
  }

  final def ensureByName(tpe: TypeRepr): TypeRepr = {
    tpe match {
      case t @ ByNameType(_) => t
      case t => ByNameType(t)
    }
  }

  @tailrec final def returnTypeOfMethodOrByName(tpe: TypeRepr): TypeRepr = {
    tpe match {
      case ByNameType(t) =>
        returnTypeOfMethodOrByName(t)
      case MethodType(_, _, t) =>
        returnTypeOfMethodOrByName(t)
      case PolyType(_, _, t) =>
        returnTypeOfMethodOrByName(t)
      case t =>
        t
    }
  }

  // FIXME duplicates FunctoidMacro.FunctoidParametersMacro#dropByName
  @tailrec final def returnTypeOfByName(tpe: TypeRepr): TypeRepr = {
    tpe match {
      case ByNameType(t) =>
        returnTypeOfByName(t)
      case t =>
        t
    }
  }

  def dereferenceTypeRef(tpe: TypeRepr): TypeRepr = {
    tpe match {
      case t: TypeRef =>
        t.typeSymbol.owner.typeRef.memberType(t.typeSymbol)
      case _ =>
        tpe
    }
  }

  def readWithAnnotation(name: String, annotSym: Option[Symbol], tpe: TypeRepr): Option[TypeRepr] = {
    ReflectionUtil.readTypeOrSymbolDIAnnotation(withAnnotationSym)(name, annotSym, Right(tpe)) {
      case Apply(TypeApply(Select(New(_), _), c :: _), _) =>
        Some(c.tpe)
      case aterm =>
        report.errorAndAbort(
          s"distage.With annotation expects one type argument but got malformed tree ${aterm.show} ($aterm) : ${aterm.tpe}\n\nFull type was ${tpe.show} ($tpe)"
        )
    }
  }

  def findRequiredImplParents(resultTpeSym: Symbol, resultTpe: TypeRepr): List[Symbol] = {
    if (!resultTpeSym.flags.is(Flags.Trait) && !(try
        dereferenceTypeRef(resultTpeSym.typeRef) match {
          case t: AndOrType => ReflectionUtil.intersectionUnionMembers(t).forall(_.typeSymbol.flags.is(Flags.Trait))
          case _ => false
        }
      catch {
        case t: Throwable =>
          throw new RuntimeException(s"Bad symbol ${resultTpeSym.isNoSymbol} $resultTpeSym ($resultTpe | ${resultTpe.show}) ${resultTpeSym.methodMembers} $t")
      })) {
      List(resultTpeSym)
    } else {
      val banned = mutable.HashSet[Symbol](defn.ObjectClass, defn.MatchableClass, defn.AnyRefClass, defn.AnyValClass, defn.AnyClass)
      val seen = mutable.HashSet.empty[Symbol]
      seen.addAll(banned)

      def go(sym: Symbol): List[Symbol] = {
        val onlyBases = sym.typeRef.baseClasses
          .drop(1) // without own type
          .filterNot(seen)

        if (!sym.flags.is(Flags.Trait)) {
          // (abstract) class calls the constructors of its bases, so don't call constructors for any of its bases
          def banAll(s: Symbol): Unit = {
            val onlyBasesNotBanned = s.typeRef.baseClasses.drop(1).filterNot(banned)
            seen ++= onlyBasesNotBanned
            banned ++= onlyBasesNotBanned
            onlyBasesNotBanned.foreach(banAll)
          }

          banAll(sym)
          List(sym)
        } else {
          seen ++= onlyBases
          val needConstructorCall = onlyBases.filter(
            s =>
              !s.flags.is(Flags.Trait) || (
                s.primaryConstructor.paramSymss.nonEmpty
                && s.primaryConstructor.paramSymss.exists(_.headOption.exists(!_.isTypeParam))
              )
          )
          needConstructorCall ++ onlyBases.flatMap(go)
        }
      }

      val (classCtors0, traitCtors0) = go(resultTpeSym).filterNot(banned).distinct.partition(!_.flags.is(Flags.Trait))
      val classCtors = if (classCtors0.isEmpty) List(defn.ObjectClass) else classCtors0
      val traitCtors =
        // try to instantiate traits in order from deeper to shallower
        // (allow traits defined later in the hierarchy to override their base traits)
        (resultTpeSym :: traitCtors0).reverse
      classCtors ++ traitCtors
    }
  }

  def extractConstructorParamLists(tpe: TypeRepr): ParamReprLists = {
    val ctorMethodTypeApplied =
      try {
        tpe.memberType(tpe.typeSymbol.primaryConstructor).appliedTo(tpe.typeArgs)
      } catch {
        case t: Throwable =>
          throw new RuntimeException(s"Got $t in tpe=${tpe.show} prim=${tpe.typeSymbol.primaryConstructor}, pt=${tpe.typeSymbol.primaryConstructor.typeRef}")
      }

    extractMethodParamLists(ctorMethodTypeApplied, tpe.typeSymbol.primaryConstructor)
  }

  def buildConstructorTermAppliedToTypeParameters(resultType: TypeRepr): Term = {
    resultType.typeSymbol.primaryConstructor match {
      case s if s.isNoSymbol =>
        report.errorAndAbort(s"Cannot find primary constructor in $resultType")
      case consSym =>
        val ctorTree = Select(New(TypeTree.of(using resultType.asType)), consSym)
        ctorTree.appliedToTypeTrees(resultType.typeArgs.map(tArg => TypeTree.of(using tArg.asType)))
    }
  }

  def buildParentConstructorCallTerms(
    constructorParamListsRepr: List[(TypeRepr, ParamReprLists)],
    outerLamArgs: List[Term],
  ): Seq[Term] = {
    import scala.collection.immutable.Queue
    val (_, parents) = constructorParamListsRepr.foldLeft((outerLamArgs, Queue.empty[Term])) {
      case ((remainingLamArgs, doneCtors), (parentType, ctorParamListsRepr)) =>
        val ctorTreeParameterized = buildConstructorTermAppliedToTypeParameters(parentType)
        val (rem, argsLists) = ctorParamListsRepr.foldLeft((remainingLamArgs, Queue.empty[List[Term]])) {
          case ((lamArgs, res), params) =>
            val (argList, rest) = lamArgs.splitAt(params.size)
            (rest, res :+ argList)
        }

        val appl = argsLists.foldLeft(ctorTreeParameterized)(_.appliedToArgs(_))
        (rem, doneCtors :+ appl)
    }

    parents
  }

  def symbolIsTraitOrAbstract(typeSym: Symbol): Boolean = {
    typeSym.flags.is(Flags.Trait) || typeSym.flags.is(Flags.Abstract)
  }

  // FIXME: move back to FactoryConstructor
  object factoryUtil {

    sealed trait FactoryProductParameter
    final case class InjectedDependencyParameter(
      depByNameParamRepr: ParamRepr,
      flatOutermostLambdaSigIndex: Int,
    ) extends FactoryProductParameter
    final case class MethodParameter(/*sigName: String, tpe: TypeRepr, */ flatLocalSigIndex: Int) extends FactoryProductParameter

    final case class FactoryProductData(
//      name: String,
      getFactoryProductType: List[TypeTree] => TypeRepr,
//      implTypeSym: Symbol,
//      dependencies: List[InjectedDependencyParameter],
      byNameDependencies: List[ParamRepr],
      hackyTraitImpl: Option[(List[TypeTree], List[Term], Symbol, List[Term], Int) => Term],
      factoryProductParameterLists: List[List[FactoryProductParameter]],
    )

    def getFactoryProductData(
      resultTpe: TypeRepr
    )(flatLambdaSigIndexGetAndIncrement: () => Int
    )(methodName: String,
      mbMethodSym: Option[Symbol],
      methodType: TypeRepr,
    ): FactoryProductData = {

      requireConcreteTypeConstructor(resultTpe, "FactoryConstructor")

      val getFactoryProductType = {
        (methodTypeArgs: List[TypeTree]) =>

          val rettAppliedProperly = methodType match {
            case p: PolyType =>
              p.appliedTo(methodTypeArgs.map(_.tpe))
            case _ =>
              methodType
          }
          val rettAppliedForcefully = returnTypeOfMethodOrByName(rettAppliedProperly)

          val res = (readWithAnnotation(methodName, mbMethodSym, rettAppliedForcefully), mbMethodSym) match {
            case (Some(withResult), Some(methodSym)) =>
              // FIXME perform manual substition to work around https://github.com/lampepfl/dotty/issues/16468
              //       for a very limited case where we have `X[F] @With[X.Impl[F]]` - F has to be present both on lhs
              //       and on rhs for this to work
              val rettUnapplied = returnTypeOfMethodOrByName(methodSym.owner.typeRef.memberType(methodSym))
              (rettAppliedForcefully.dealias.simplified, rettUnapplied.dealias.simplified, withResult.dealias.simplified) match {
                case (AppliedType(_, rArgs), AppliedType(_, uArgs), AppliedType(wCtor, wArgs)) =>
                  val untypedSymToResolvedType = uArgs.map(_.typeSymbol).zip(rArgs).toMap
                  AppliedType(wCtor, wArgs.map(w => untypedSymToResolvedType.getOrElse(w.typeSymbol, w)))
                case _ =>
                  withResult
              }
            case (Some(withResult), _) =>
              withResult
            case (None, _) =>
              rettAppliedForcefully.dealias.simplified
          }

          res.dealias.simplified
      }
      val factoryProductType = getFactoryProductType(Nil)

      val isTrait = symbolIsTraitOrAbstract(factoryProductType.typeSymbol)

      val ctxUntyped = new ConstructorContext[Any, qctx.type, self.type & ConstructorUtil[qctx.type]](
        using factoryProductType.asType.asInstanceOf[Type[Any]],
        qctx,
      )(self.asInstanceOf[self.type & ConstructorUtil[qctx.type]])

      if (isTrait) {
        ctxUntyped.assertIsWireableTrait(isInFactoryConstructor = true)
      }

      val factoryProductCtorParamLists = if (isTrait) {
        val byNameMethodArgs = ctxUntyped.methodDecls.map {
          case MemberRepr(n, _, s, t, _) => ParamRepr(n, s, returnTypeOfMethodOrByName(t))
        } // become byName later via ensureByName if they're InjectedDependencyParameter
        ctxUntyped.constructorParamLists.flatMap(_._2) :+ byNameMethodArgs
      } else {
        extractConstructorParamLists(factoryProductType)
      }
      assertSignatureIsAcceptableForFactory(factoryProductCtorParamLists.flatten, resultTpe, s"implementation constructor ${factoryProductType.show}")

      val methodParams = extractMethodParamLists(methodType, mbMethodSym.getOrElse(Symbol.noSymbol)).flatten
      assertSignatureIsAcceptableForFactory(methodParams, resultTpe, s"factory method $methodName")

      val indexedMethodParams = methodParams.zipWithIndex
      val methodParamIndex = indexedMethodParams.map { case (ParamRepr(n, _, t), idx) => (t, (n, idx)) }

      val factoryProductParamss = factoryProductCtorParamLists.zipWithIndex.map {
        case (params, paramListIdx) =>
          params.map {
            case ParamRepr(paramName, symbol, paramType) =>
              methodParamIndex.filter((t, _) => returnTypeOfMethodOrByName(t) =:= returnTypeOfMethodOrByName(paramType)) match {
                case (_, (_, idx)) :: Nil =>
                  MethodParameter( /*paramName, paramType, */ idx)

                case Nil =>
                  val curIndex = flatLambdaSigIndexGetAndIncrement()
                  val newName = if (paramListIdx > 0) {
                    s"_${methodName}_${paramListIdx}_$paramName"
                  } else {
                    s"_${methodName}_$paramName"
                  }
                  InjectedDependencyParameter(ParamRepr(newName, symbol, ensureByName(paramType)), curIndex)

                case multiple =>
                  val (_, (_, idx)) = multiple
                    .find { case (_, (n, _)) => n == paramName }
                    .getOrElse(
                      report.errorAndAbort(
                        s"""Couldn't disambiguate between multiple arguments with the same type available for parameter $paramName: ${paramType.show} of ${factoryProductType.show} constructor
                           |Expected one of the arguments to be named `$paramName` or for the type to be unique among factory method arguments""".stripMargin
                      )
                    )
                  MethodParameter(idx)
              }
          }
      }

      val consumedSigParams = factoryProductParamss.flatten.collect { case p: MethodParameter => p.flatLocalSigIndex }.toSet
      val unconsumedParameters = indexedMethodParams.filterNot(p => consumedSigParams.contains(p._2))

      if (unconsumedParameters.nonEmpty) {
        import izumi.fundamentals.platform.strings.IzString.*
        val explanation = unconsumedParameters.map { case (ParamRepr(n, _, t), _) => s"$n: ${t.show}" }.niceList()
        report.errorAndAbort(
          s"""Cannot build factory for ${resultTpe.show}, factory method $methodName has arguments which were not consumed by implementation constructor ${factoryProductType.show}:$explanation
             |Factory product dependencies were: ${factoryProductCtorParamLists.map(_.map(_.tpe.show)).niceList()}
             |
             |raw-resultTpe: $resultTpe
             |raw-factoryProductType: $factoryProductType
             |raw-methodType: $methodType
             |raw-dependencies: ${factoryProductCtorParamLists.map(_.map(_.tpe)).niceList()}""".stripMargin
        )
      }

      val hackySecretTraitImpl = if (isTrait) {
        Some(createHackySecretTraitImpl(getFactoryProductType, factoryProductParamss))
      } else {
        None
      }

      FactoryProductData(
        getFactoryProductType,
        factoryProductParamss.flatten.collect { case p: InjectedDependencyParameter => p.depByNameParamRepr },
        hackySecretTraitImpl,
        factoryProductParamss,
      )
    }

    private def createHackySecretTraitImpl(
      getFactoryProductType: List[TypeTree] => TypeRepr,
      factoryProductParamss: List[List[FactoryProductParameter]],
    )(typeArgs: List[TypeTree],
      outermostLambdaArgs: List[Term],
      lamSym: Symbol,
      thisMethodArgs: List[Term],
      indexShift: Int,
    ): Typed = {
      val ctxTyped =
        new ConstructorContext[Any, qctx.type, self.type & ConstructorUtil[qctx.type]](
          using getFactoryProductType(typeArgs).asType.asInstanceOf[Type[Any]],
          qctx,
        )(self.asInstanceOf[self.type & ConstructorUtil[qctx.type]])

      val (lamOnlyCtorArguments, lamOnlyMethodArguments) = {
        factoryProductParamss.flatten
          .map {
            case p: InjectedDependencyParameter =>
              outermostLambdaArgs(p.flatOutermostLambdaSigIndex + indexShift)
            case p: MethodParameter =>
              thisMethodArgs(p.flatLocalSigIndex)
          }
          .splitAt(ctxTyped.flatCtorParams.size)
      }

      ctxTyped.implementTraitAutoImplBody(lamSym, lamOnlyCtorArguments, lamOnlyMethodArguments)
    }

    def implementFactoryMethod(
      outermostLambdaArgs: List[Term],
      factoryProductData: FactoryProductData,
      methodSym: Symbol,
      indexShift: Int,
    ): DefDef = {
      val FactoryProductData(getFactoryProductType, _, hackySecretTraitImpl, factoryProductParameterLists) = factoryProductData
      DefDef(
        methodSym,
        thisMethodArgs0 => {
          val (thisMethodArgs, thisMethodTypeArgs) = thisMethodArgs0.flatten.partitionMap { case t: Term => Left(t); case t: TypeTree => Right(t) }

          Some(hackySecretTraitImpl match {
            case Some(hackyHacky) =>
              hackyHacky(
                thisMethodTypeArgs,
                outermostLambdaArgs,
                methodSym,
                thisMethodArgs,
                indexShift,
              )
            case None =>
              val factoryProductType = getFactoryProductType(thisMethodTypeArgs)

              val ctorTreeParameterized = buildConstructorTermAppliedToTypeParameters(factoryProductType)

              val argsLists: List[List[Term]] = factoryProductParameterLists.map(_.map {
                case p: InjectedDependencyParameter =>
                  outermostLambdaArgs(p.flatOutermostLambdaSigIndex + indexShift)
                case p: MethodParameter =>
                  thisMethodArgs(p.flatLocalSigIndex)
              })

              // TODO: check that there are no unconsumed parameters

              val appl = argsLists.foldLeft(ctorTreeParameterized)(_.appliedToArgs(_))
              Typed(appl, TypeTree.of(using factoryProductType.asType))
          })
        },
      )
    }

  }
}
